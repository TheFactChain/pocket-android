package net.typeblog.shelter;

import android.content.Context;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.SetupOptions;
import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.PlatformInterface;
import net.typeblog.shelter.connections.ConnectionProfile;
import net.typeblog.shelter.vpn.PocketConfig;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PocketCoreTest {
    @Test public void commandServerDoesNotPersistSentinelConfiguration() throws Exception {
        Context context=InstrumentationRegistry.getTargetContext();
        // Unix socket paths are limited to 108 bytes on Android.
        java.io.File directory=new java.io.File(context.getCacheDir(),"sc-"+java.util.UUID.randomUUID().toString().substring(0,8));
        assertTrue(directory.mkdirs());go.Seq.setContext(context);
        SetupOptions setup=new SetupOptions();setup.setBasePath(directory.getAbsolutePath());
        setup.setWorkingPath(directory.getAbsolutePath());setup.setTempPath(directory.getAbsolutePath());
        setup.setCommandServerSecret(java.util.UUID.randomUUID().toString());setup.setOomKillerDisabled(true);
        Libbox.setup(setup);
        java.lang.reflect.InvocationHandler inert=(proxy,method,args) -> {
            if(method.getName().equals("openTun"))throw new AssertionError("This test must never create a VPN");
            if(method.getName().equals("getSystemProxyStatus"))return new io.nekohasekai.libbox.SystemProxyStatus();
            if(method.getReturnType()==boolean.class)return false;
            if(method.getReturnType()==int.class)return 0;
            if(method.getReturnType()==long.class)return 0L;
            return null;
        };
        PlatformInterface platform=(PlatformInterface)java.lang.reflect.Proxy.newProxyInstance(
                PlatformInterface.class.getClassLoader(),new Class<?>[]{PlatformInterface.class},inert);
        CommandServerHandler handler=(CommandServerHandler)java.lang.reflect.Proxy.newProxyInstance(
                CommandServerHandler.class.getClassLoader(),new Class<?>[]{CommandServerHandler.class},inert);
        String sentinel="local-test-sentinel-"+java.util.UUID.randomUUID();
        ConnectionProfile profile=ConnectionProfile.fromJson(new JSONObject().put("type","hysteria2")
                .put("server","192.0.2.1").put("port",9).put("password",sentinel).put("serverName","example.com"));
        JSONObject config=new JSONObject().put("log",new JSONObject().put("disabled",true))
                .put("outbounds",new org.json.JSONArray().put(profile.toOutbound()))
                .put("route",new JSONObject().put("final","proxy"));
        CommandServer server=Libbox.newCommandServer(handler,platform);
        try {
            server.start();server.startOrReloadService(config.toString(),new io.nekohasekai.libbox.OverrideOptions());
            assertFalse(new java.io.File(directory,"configuration.json").exists());
            try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(directory.toPath())){
                for(java.nio.file.Path path:(Iterable<java.nio.file.Path>)paths.filter(java.nio.file.Files::isRegularFile)::iterator){
                    assertFalse("Secret in "+path.getFileName(),new String(java.nio.file.Files.readAllBytes(path),java.nio.charset.StandardCharsets.ISO_8859_1).contains(sentinel));
                }
            }
        }finally{try{server.closeService();}finally{server.close();}}
    }
    @Test public void compiledCoreAcceptsBothRestrictedConfigurations() throws Exception {
        Context context=InstrumentationRegistry.getTargetContext();
        go.Seq.setContext(context);
        SetupOptions setup=new SetupOptions();
        setup.setBasePath(context.getCacheDir().getAbsolutePath());
        setup.setWorkingPath(context.getCacheDir().getAbsolutePath());
        setup.setTempPath(context.getCacheDir().getAbsolutePath());
        setup.setCommandServerSecret(java.util.UUID.randomUUID().toString());
        setup.setFixAndroidStack(true);setup.setOomKillerDisabled(true);
        Libbox.setup(setup);
        assertEquals("1.14.4",Libbox.version());
        ConnectionProfile hysteria=ConnectionProfile.fromJson(new JSONObject().put("type","hysteria2")
                .put("server","example.com").put("port",443).put("password","test-only"));
        Libbox.checkConfig(PocketConfig.create(hysteria,"192.0.2.1"));
        ConnectionProfile reality=ConnectionProfile.fromJson(new JSONObject().put("type","vless")
                .put("server","example.com").put("port",443).put("uuid","123e4567-e89b-12d3-a456-426614174000")
                .put("serverName","example.com").put("realityPublicKey","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                .put("shortId","0123456789abcdef").put("flow","xtls-rprx-vision"));
        Libbox.checkConfig(PocketConfig.create(reality,"192.0.2.1"));
    }
}
