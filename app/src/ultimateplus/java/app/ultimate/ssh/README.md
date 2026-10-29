# SSH and SFTP integration

Ultimate+ only. The server is off at every process start and is never started by boot or a saved preference. Open the SSH and SFTP tool, paste OpenSSH public keys or select a public-key QR image, approve them, grant shared storage and notification access, then turn it on. Login is `ultimate`, port `8022`. Compare the displayed SHA256 host fingerprint with the computer's SSH prompt.

Password, keyboard-interactive, none authentication, forwarding and SFTP link creation are disabled. SFTP exposes shared storage subject to Android permissions. This is a filesystem view, not a sandbox for the SSH shell: approved keys can run commands with the app UID and permissions. Root is not granted. SSH is independent of the dashboard connection; the dashboard terminal/secure-mode integration belongs to the terminal work.

The notification remains present while listening, including before a client connects. Disconnect closes all authenticated sessions; Turn off SSH closes the listener and pending unauthenticated sessions too. Notification or all-files permission loss shuts down the service. The service is non-exported and non-sticky.

The EC P-256 host key is generated into app-private files, with a stable SHA256 fingerprint. Corrupt host-key data fails startup instead of regenerating the host identity. Keys and enabled state are never exported. App backup is already disabled by the main manifest.

## PTY integration

Call `SshServerManager.get(context).setShellSessionFactory(factory)` while stopped. `ShellSession.Factory.open(command, columns, rows)` returns a new instance per channel. Null command means interactive shell; otherwise it is an SSH exec request. The initial implementation uses `/system/bin/sh` with stdin/stdout/stderr pipes, reports exit status, and destroys the owned shell on channel close. It has no PTY, resize events, job control, or guarantee of terminating detached child jobs. Terminal integration should supply those through the shared PTY implementation.

QR scanning decodes an image from Android's picker locally. Live camera scanning and optional password login are not implemented.

## Verification

`gradle :app:testUltimateplusDebugUnitTest :app:lintUltimateplusRelease :app:assembleUltimateplusRelease :app:assembleUltimateRelease`

JVM integration tests start real loopback SSH/SFTP clients and cover approved and rejected authentication, exec streams and exit codes, shared-storage reads/writes, traversal/symlink rejection, disconnect/reconnect, forwarding rejection, stable/corrupt host identity and atomic approval validation. They exercise the same pure-Java engine; they do not establish Android service behavior, notification rendering or device compatibility. Test on a phone before enabling this in a release.

Current local results: 218 unit tests passed (9 SSH integration tests); `lintUltimateplusRelease` passed with no findings in the SSH package. `assembleUltimateplusRelease` passed with R8 enabled, and the APK manifest and DEX retain the private SSH service and reflective tool entrypoint. No Android device was attached for runtime testing.

`assembleUltimateRelease` also passed with R8 enabled. Inspection confirmed that the offline APK contains no INTERNET permission, SSH manifest components, SSH classes or MINA classes.
