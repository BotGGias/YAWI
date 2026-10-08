# Development Key for Manifest Signatures 

**Development only.** The private key in this directory is
public (it is stored in the repository) and provides no protection whatsoever. It exists
so that `sign-manifest`, the tests, and a locally signed manifest work without any additional
setup.

| File                       | Purpose                                                                                                                                            |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| `manifest-signing.key.pem` | private key, PKCS#8, unencrypted (RSA 3072)                                                                                                        |
| `manifest-signing.pub.pem` | public key, X.509/SPKI — identical to `src/main/resources/de/yawi/installer/integrity/manifest-signing.pub.pem`, the key embedded in the installer |

## For a Release

1. Generate your own key pair — using the tool or OpenSSL:

   ```sh
   ./mvnw -q compile exec:java@sign-manifest -Dyawi.sign.args="keygen /secure/location"
   # or
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out manifest-signing.key.pem
   openssl pkey -in manifest-signing.key.pem -pubout -out manifest-signing.pub.pem
   ```
2. Copy the **public** key to
   `src/main/resources/de/yawi/installer/integrity/manifest-signing.pub.pem`
   and build the installer. The private key remains outside the
   repository (e.g. as a CI secret).
3. Sign manifests (`sign`, see README) and place `installer.xml.sig` next to the
   manifest.

Changing the key requires a new installer build because the
public key is embedded in the installer.
