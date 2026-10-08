/**
 * Integrity (E10): {@link de.yawi.installer.core.integrity.ChecksumVerifier}
 * checks artifacts against the manifest's {@code sha256} while they are
 * written; {@link de.yawi.installer.core.integrity.ManifestSignatureVerifier}
 * checks a manifest's detached signature under the embedded key, as strictly
 * as the build's {@link de.yawi.installer.core.integrity.SignaturePolicy}
 * demands; {@link de.yawi.installer.core.integrity.ManifestSigner} is the
 * packager's tool for keys and signatures; the
 * {@link de.yawi.installer.core.integrity.PathGuard} (E10-S03) keeps file
 * steps and archive entries inside their roots.
 */
package de.yawi.installer.core.integrity;
