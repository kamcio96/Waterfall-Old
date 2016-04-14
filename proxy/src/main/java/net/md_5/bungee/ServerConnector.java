package net.md_5.bungee;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.Arrays;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.score.Objective;
import net.md_5.bungee.api.score.Scoreboard;
import net.md_5.bungee.api.score.Team;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.connection.CancelSendSignal;
import net.md_5.bungee.connection.DownstreamBridge;
import net.md_5.bungee.connection.LoginResult;
import net.md_5.bungee.forge.ForgeConstants;
import net.md_5.bungee.forge.ForgeServerHandler;
import net.md_5.bungee.forge.ForgeUtils;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.netty.HandlerBoss;
import net.md_5.bungee.netty.PacketHandler;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.MinecraftDecoder;
import net.md_5.bungee.protocol.MinecraftOutput;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.packet.BossBar;
import net.md_5.bungee.protocol.packet.EncryptionRequest;
import net.md_5.bungee.protocol.packet.Handshake;
import net.md_5.bungee.protocol.packet.Kick;
import net.md_5.bungee.protocol.packet.Login;
import net.md_5.bungee.protocol.packet.LoginSuccess;
import net.md_5.bungee.protocol.packet.PluginMessage;
import net.md_5.bungee.protocol.packet.Respawn;
import net.md_5.bungee.protocol.packet.ScoreboardObjective;
import net.md_5.bungee.protocol.packet.SetCompression;

@RequiredArgsConstructor
public class ServerConnector extends PacketHandler
{

    private final ProxyServer bungee;
    private ChannelWrapper ch;
    private final UserConnection user;
    private final BungeeServerInfo target;
    private State thisState = State.LOGIN_SUCCESS;
    @Getter
    private ForgeServerHandler handshakeHandler;
    private boolean obsolete;

    private enum State
    {

        LOGIN_SUCCESS, ENCRYPT_RESPONSE, LOGIN, FINISHED;
    }

    @Override
    public void exception(Throwable t) throws Exception
    {
        if ( obsolete )
        {
            return;
        }

        String message = "Exception Connecting:" + Util.exception( t );
        if ( user.getServer() == null )
        {
            user.disconnect( message );
        } else
        {
            user.sendMessage( ChatColor.RED + message );
        }
    }

    @Override
    public void connected(ChannelWrapper channel) throws Exception
    {
        this.ch = channel;

        this.handshakeHandler = new ForgeServerHandler( user, ch, target );
        Handshake originalHandshake = user.getPendingConnection().getHandshake();
        Handshake copiedHandshake = new Handshake( originalHandshake.getProtocolVersion(), originalHandshake.getHost(), originalHandshake.getPort(), 2 );

        // Waterfall start - use per-server client detail forwarding
        //if ( BungeeCord.getInstance().config.isIpForward() )
        if ( this.target.shouldForwardClientDetails() )
        // Waterfall end
        {
            String newHost = copiedHandshake.getHost() + "\00" + user.getAddress().getHostString() + "\00" + user.getUUID();

            LoginResult profile = user.getPendingConnection().getLoginProfile();

            // Handle properties.
            LoginResult.Property[] properties = new LoginResult.Property[0];

            if ( profile != null && profile.getProperties() != null && profile.getProperties().length > 0 )
            {
                properties = profile.getProperties();
            }

            if ( user.getForgeClientHandler().isFmlTokenInHandshake() )
            {
                // Get the current properties and copy them into a slightly bigger array.
                LoginResult.Property[] newp = Arrays.copyOf( properties, properties.length + 2 );

                // Add a new profile property that specifies that this user is a Forge user.
                newp[newp.length - 2] = new LoginResult.Property( ForgeConstants.FML_LOGIN_PROFILE, "true", null );

                // If we do not perform the replacement, then the IP Forwarding code in Spigot et. al. will try to split on this prematurely.
                newp[newp.length - 1] = new LoginResult.Property( ForgeConstants.EXTRA_DATA, user.getExtraDataInHandshake().replaceAll( "\0", "\1"), "" );

                // All done.
                properties = newp;
            }

            // If we touched any properties, then append them
            if (properties.length > 0) {
                newHost += "\00" + BungeeCord.getInstance().gson.toJson(properties);
            }

            // Waterfall start - If we have a shared secret, append it
            if (this.target.getSharedSecret() != null) {
                newHost += "\00" + this.target.getSharedSecret();
            }
            // Waterfall end

            copiedHandshake.setHost( newHost );
        } else if ( !user.getExtraDataInHandshake().isEmpty() )
        {
            // Restore the extra data
            copiedHandshake.setHost( copiedHandshake.getHost() + user.getExtraDataInHandshake() );
        }

        channel.write( copiedHandshake );

        channel.setProtocol( Protocol.LOGIN );
        channel.write( user.getPendingConnection().getLoginRequest() );
    }

    @Override
    public void disconnected(ChannelWrapper channel) throws Exception
    {
        user.getPendingConnects().remove( target );
    }

    @Override
    public void handle(LoginSuccess loginSuccess) throws Exception
    {
        Preconditions.checkState( thisState == State.LOGIN_SUCCESS, "Not expecting LOGIN_SUCCESS" );
        ch.setProtocol( Protocol.GAME );
        thisState = State.LOGIN;

        // Only reset the Forge client when:
        // 1) The user is switching servers (so has a current server)
        // 2) The handshake is complete
        // 3) The user is currently on a modded server (if we are on a vanilla server,
        //    we may be heading for another vanilla server, so we don't need to reset.)
        //
        // user.getServer() gets the user's CURRENT server, not the one we are trying
        // to connect to.
        //
        // We will reset the connection later if the current server is vanilla, and
        // we need to switch to a modded connection. However, we always need to reset the
        // connection when we have a modded server regardless of where we go - doing it
        // here makes sense.
        if ( user.getServer() != null && user.getForgeClientHandler().isHandshakeComplete()
                && user.getServer().isForgeServer() )
        {
            user.getForgeClientHandler().resetHandshake();
        }

        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(SetCompression setCompression) throws Exception
    {
        ch.setCompressionThreshold( setCompression.getThreshold() );
    }

    @Override
    public void handle(Login login) throws Exception
    {
        Preconditions.checkState( thisState == State.LOGIN, "Not expecting LOGIN" );

        ServerConnection server = new ServerConnection( ch, target );
        ServerConnectedEvent event = new ServerConnectedEvent( user, server );

        bungee.getPluginManager().callEvent( event );

        ch.write( BungeeCord.getInstance().registerChannels() );
        Queue<DefinedPacket> packetQueue = target.getPacketQueue();
        synchronized ( packetQueue )
        {
            while ( !packetQueue.isEmpty() )
            {
                ch.write( packetQueue.poll() );
            }
        }

        for ( PluginMessage message : user.getPendingConnection().getRegisterMessages() )
        {
            ch.write( message );
        }

        if ( user.getSettings() != null )
        {
            ch.write( user.getSettings() );
        }

        if ( user.getForgeClientHandler().getClientModList() == null && !user.getForgeClientHandler().isHandshakeComplete() ) // Vanilla
        {
            user.getForgeClientHandler().setHandshakeComplete();
        }

        if ( user.getServer() == null )
        {
            // Once again, first connection
            user.setClientEntityId( login.getEntityId() );
            user.setServerEntityId( login.getEntityId() );

            // Set tab list size, this sucks balls, TODO: what shall we do about packet mutability
            Login modLogin = new Login( login.getEntityId(), login.getGameMode(), (byte) login.getDimension(), login.getDifficulty(),
                    (byte) user.getPendingConnection().getListener().getTabListSize(), login.getLevelType(), login.isReducedDebugInfo() );

            user.unsafe().sendPacket( modLogin );

            if ( user.getPendingConnection().getVersion() < ProtocolConstants.MINECRAFT_1_8 )
            {
                MinecraftOutput out = new MinecraftOutput();
                out.writeStringUTF8WithoutLengthHeaderBecauseDinnerboneStuffedUpTheMCBrandPacket( ProxyServer.getInstance().getName() + " (" + ProxyServer.getInstance().getVersion() + ")" );
                user.unsafe().sendPacket( new PluginMessage( "MC|Brand", out.toArray(), handshakeHandler.isServerForge() ) );
            } else
            {
                ByteBuf brand = ByteBufAllocator.DEFAULT.heapBuffer();
                DefinedPacket.writeString( bungee.getName() + " (" + bungee.getVersion() + ")", brand );
                user.unsafe().sendPacket( new PluginMessage( "MC|Brand", brand.array().clone(), handshakeHandler.isServerForge() ) );
                brand.release();
            }
        } else
        {
            user.getServer().setObsolete( true );
            user.getTabListHandler().onServerChange();

            Scoreboard serverScoreboard = user.getServerSentScoreboard();
            for ( Objective objective : serverScoreboard.getObjectives() )
            {
                user.unsafe().sendPacket( new ScoreboardObjective( objective.getName(), objective.getValue(), "integer", (byte) 1 ) ); // TODO:
            }
            for ( Team team : serverScoreboard.getTeams() )
            {
                user.unsafe().sendPacket( new net.md_5.bungee.protocol.packet.Team( team.getName() ) );
            }
            serverScoreboard.clear();

            for ( UUID bossbar : user.getSentBossBars() )
            {
                // Send remove bossbar packet
                user.unsafe().sendPacket( new net.md_5.bungee.protocol.packet.BossBar( bossbar, 1 ) );
            }
            user.getSentBossBars().clear();

            user.sendDimensionSwitch();

            user.setServerEntityId( login.getEntityId() );
            user.unsafe().sendPacket( new Respawn( login.getDimension(), login.getDifficulty(), login.getGameMode(), login.getLevelType() ) );

            // Remove from old servers
            user.getServer().disconnect( "Quitting" );
        }

        // TODO: Fix this?
        if ( !user.isActive() )
        {
            server.disconnect( "Quitting" );
            // Silly server admins see stack trace and die
            bungee.getLogger().warning( "No client connected for pending server!" );
            return;
        }

        // Add to new server
        // TODO: Move this to the connected() method of DownstreamBridge
        target.addPlayer( user );
        user.getPendingConnects().remove( target );
        user.setServerJoinQueue( null );
        user.setDimensionChange( false );

        user.setServer( server );
        ch.getHandle().pipeline().get( HandlerBoss.class ).setHandler( new DownstreamBridge( bungee, user, server ) );

        bungee.getPluginManager().callEvent( new ServerSwitchEvent( user ) );

        thisState = State.FINISHED;

        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(EncryptionRequest encryptionRequest) throws Exception
    {
        throw new RuntimeException( "Server is online mode!" );
    }

    @Override
    public void handle(Kick kick) throws Exception
    {
        ServerInfo def = user.updateAndGetNextServer( target );
        ServerKickEvent event = new ServerKickEvent( user, target, ComponentSerializer.parse( kick.getMessage() ), def, ServerKickEvent.State.CONNECTING );
        if ( event.getKickReason().toLowerCase().contains( "outdated" ) && def != null )
        {
            // Pre cancel the event if we are going to try another server
            event.setCancelled( true );
        }
        bungee.getPluginManager().callEvent( event );
        if ( event.isCancelled() && event.getCancelServer() != null )
        {
            obsolete = true;
            user.connect( event.getCancelServer() );
            throw CancelSendSignal.INSTANCE;
        }

        String message = bungee.getTranslation( "connect_kick", target.getName(), event.getKickReason() );
        if ( user.isDimensionChange() )
        {
            user.disconnect( message );
        } else
        {
            user.sendMessage( message );
        }

        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(PluginMessage pluginMessage) throws Exception
    {
        if ( pluginMessage.getTag().equals( ForgeConstants.FML_REGISTER ) )
        {
            Set<String> channels = ForgeUtils.readRegisteredChannels( pluginMessage );
            boolean isForgeServer = false;
            for ( String channel : channels )
            {
                if ( channel.equals( ForgeConstants.FML_HANDSHAKE_TAG ) )
                {
                    // If we have a completed handshake and we have been asked to register a FML|HS
                    // packet, let's send the reset packet now. Then, we can continue the message sending.
                    // The handshake will not be complete if we reset this earlier.
                    if ( user.getServer() != null && user.getForgeClientHandler().isHandshakeComplete() )
                    {
                        user.getForgeClientHandler().resetHandshake();
                    }

                    isForgeServer = true;
                    break;
                }
            }

            if ( isForgeServer && !this.handshakeHandler.isServerForge() )
            {
                // We now set the server-side handshake handler for the client to this.
                handshakeHandler.setServerAsForgeServer();
                user.setForgeServerHandler( handshakeHandler );
            }
        }

        if ( pluginMessage.getTag().equals( ForgeConstants.FML_HANDSHAKE_TAG ) || pluginMessage.getTag().equals( ForgeConstants.FORGE_REGISTER ) )
        {
            this.handshakeHandler.handle( pluginMessage );
            if (user.getForgeClientHandler().checkUserOutdated()) {
                ch.close();
                user.getPendingConnects().remove(target);
            }

            // We send the message as part of the handler, so don't send it here.
            throw CancelSendSignal.INSTANCE;
        } else
        {
            // We have to forward these to the user, especially with Forge as stuff might break
            // This includes any REGISTER messages we intercepted earlier.
            user.unsafe().sendPacket( pluginMessage );
        }
    }

    @Override
    public String toString()
    {
        return "[" + user.getAddress() + "|" + user.getName() + "] <-> ServerConnector [" + target.getName() + "]";
    }
}
