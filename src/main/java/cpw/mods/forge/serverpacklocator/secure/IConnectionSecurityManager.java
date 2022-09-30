package cpw.mods.forge.serverpacklocator.secure;

import com.electronwill.nightconfig.core.file.FileConfig;
import io.netty.handler.codec.http.FullHttpRequest;

import java.net.URLConnection;

public interface IConnectionSecurityManager
{
    void onClientConnectionCreation(URLConnection connection, byte[] challenge);

    boolean onServerConnectionRequest(FullHttpRequest msg, byte[] challenge);
    
    boolean requiresChallenge();

    default void validateConfiguration(FileConfig config) {
        //Default is no configuration needed.
    }

    default void initialize(FileConfig config) {
        //Default is no initialization needed.
    }
}
