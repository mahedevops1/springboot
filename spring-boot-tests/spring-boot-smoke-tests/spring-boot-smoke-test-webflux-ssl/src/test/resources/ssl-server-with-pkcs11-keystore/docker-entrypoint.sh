# Initialize a SoftHSM token only if not done already, e.g. at first start
softhsm2-util --show-slots | grep "token-0" || { softhsm2-util --init-token --free --label "token-0" --pin 1234 --so-pin 0000; keytool -genkeypair -alias server -dname CN=localhost -ext san=dns:localhost -keyalg RSA -keysize 2048 -keystore NONE -storetype PKCS11 -providerclass sun.security.pkcs11.SunPKCS11 -providerarg /pkcs11.cfg -storepass 1234; keytool -exportcert -rfc  -alias server -keystore NONE -storetype PKCS11 -providerclass sun.security.pkcs11.SunPKCS11 -providerarg /pkcs11.cfg -storepass 1234 > /server-cert.pem; }
java ${JAVA_OPTS} -jar /app.jar --server.port=8443 --server.ssl.enabled=true --server.ssl.key-alias=server --server.ssl.key-store-provider=SunPKCS11-SoftHSM --server.ssl.key-store-type=PKCS11 --server.ssl.key-store-password=1234
