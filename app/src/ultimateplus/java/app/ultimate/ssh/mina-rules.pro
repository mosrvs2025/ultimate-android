# Optional server/tool entry point is registered reflectively by the main application.
-keep class app.ultimate.ssh.SshTool { public <init>(android.content.Context); }
-keep class app.ultimate.sshclient.HostsTool { public <init>(android.content.Context); }
# MINA uses service discovery and reflective factories. Preserve reflective factory and provider names.
-keep class org.apache.sshd.** { *; }
-keep class org.slf4j.** { *; }
# Optional providers/backends are not shipped: use JCA and MINA's NIO2 backend.
-dontwarn org.bouncycastle.**
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn org.apache.tomcat.jni.**

# Desktop-only client, GSS/JAAS authentication and encrypted PEM loading are not exposed.
# ExceptionUtils guards its JMX path with OsUtils.isAndroid(), explicitly set by our manager.
-dontwarn java.rmi.RemoteException
-dontwarn java.rmi.ServerException
-dontwarn javax.management.MBeanException
-dontwarn javax.management.ReflectionException
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn javax.security.auth.login.AppConfigurationEntry$LoginModuleControlFlag
-dontwarn javax.security.auth.login.AppConfigurationEntry
-dontwarn javax.security.auth.login.Configuration
-dontwarn javax.security.auth.login.CredentialException
-dontwarn javax.security.auth.login.FailedLoginException
-dontwarn javax.security.auth.login.LoginContext
-dontwarn org.ietf.jgss.**
# SLF4J intentionally uses its no-op logger when no binding is installed.
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder
-dontwarn org.slf4j.impl.StaticMarkerBinder
# EdDSA receives its own EdDSAPublicKey, never the optional desktop X509Key branch.
-dontwarn sun.security.x509.X509Key
