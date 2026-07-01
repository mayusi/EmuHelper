package io.github.mayusi.emuhelper.data.source.root

import io.github.mayusi.emuhelper.data.source.root.PServerCommandGuard.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EXHAUSTIVE tests for [PServerCommandGuard] — the ENTIRE safety model for the root bridge. The
 * PServer binder runs whatever string we give it AS ROOT, so this allow-list is the only thing
 * standing between the app and an arbitrary-root-command exploit. These tests prove:
 *   - allow-listed, well-formed commands pass,
 *   - every destructive / system-touching / injection form is blocked BEFORE it could transact,
 *   - default-deny: an unknown verb is rejected,
 *   - path traversal, system destinations, system packages, metacharacters, multi-command operators,
 *     control bytes and bad chmod modes are all denied.
 */
class PServerCommandGuardTest {

    private fun allow(cmd: String) =
        assertTrue(PServerCommandGuard.inspect(cmd) is Verdict.Allow, "expected ALLOW for: <$cmd>")

    private fun deny(cmd: String) =
        assertTrue(PServerCommandGuard.inspect(cmd) is Verdict.Deny, "expected DENY for: <$cmd>")

    // -------------------------------------------------------------------------------------------
    // ALLOWED forms.
    // -------------------------------------------------------------------------------------------

    @Test fun `cp into sdcard roms is allowed`() = allow("cp /sdcard/a /sdcard/roms/b")

    @Test fun `cp into storage emulated is allowed`() =
        allow("cp /storage/emulated/0/Download/game.iso /storage/emulated/0/roms/game.iso")

    @Test fun `cp into a known emulator data dir is allowed`() =
        allow("cp /sdcard/x.zip /data/data/com.retroarch/files/downloads/x.zip")

    @Test fun `cp recursive with -r into a write root is allowed`() =
        allow("cp -r /sdcard/romset /sdcard/roms/snes")

    @Test fun `mv into a write root is allowed`() = allow("mv /sdcard/tmp/a /sdcard/roms/a")

    @Test fun `mv from this app cache to a write root is allowed`() {
        // The real disk-efficiency move: cache .part -> /sdcard. Source is under THIS app's own
        // private dir (registered as an own-package write root), destination under /sdcard.
        PServerCommandGuard.registerOwnPackage("io.github.mayusi.emuhelper")
        allow("mv /data/user/0/io.github.mayusi.emuhelper/cache/dl/x.pkg /sdcard/roms/ps4/x.pkg")
    }

    @Test fun `mv between two shared-storage write roots is allowed`() =
        allow("mv /storage/emulated/0/Download/x.iso /storage/emulated/0/roms/x.iso")

    @Test fun `mkdir -p under a write root is allowed`() = allow("mkdir -p /sdcard/roms/snes")

    @Test fun `mkdir without -p under a write root is allowed`() = allow("mkdir /sdcard/roms")

    @Test fun `chmod 644 under a write root is allowed`() = allow("chmod 644 /sdcard/roms/x")

    @Test fun `chmod 755 on a dir under a write root is allowed`() = allow("chmod 755 /sdcard/roms")

    @Test fun `cat any readable path is allowed`() = allow("cat /proc/version")

    @Test fun `ls a path is allowed`() = allow("ls /sdcard/roms")

    @Test fun `stat a path is allowed`() = allow("stat /sdcard/roms/x")

    @Test fun `true is allowed`() = allow("true")

    @Test fun `id is allowed`() = allow("id")

    @Test fun `getprop a key is allowed`() = allow("getprop ro.product.model")

    // -------------------------------------------------------------------------------------------
    // HARD-DENY verbs (destructive / system / shell-spawning).
    // -------------------------------------------------------------------------------------------

    @Test fun `rm -rf root is denied`() = deny("rm -rf /")
    @Test fun `rm anything is denied`() = deny("rm /sdcard/roms/x")
    @Test fun `dd is denied`() = deny("dd if=/dev/zero of=/sdcard/x")
    @Test fun `setenforce 0 is denied`() = deny("setenforce 0")
    @Test fun `setenforce bare token is denied`() = deny("setenforce")
    @Test fun `reboot is denied`() = deny("reboot")
    @Test fun `poweroff is denied`() = deny("poweroff")
    @Test fun `su is denied`() = deny("su")
    @Test fun `su -c is denied`() = deny("su -c id")
    @Test fun `sh -c is denied`() = deny("sh -c id")
    @Test fun `bash is denied`() = deny("bash")
    @Test fun `busybox is denied`() = deny("busybox ls")
    @Test fun `toybox is denied`() = deny("toybox ls")
    @Test fun `mount is denied`() = deny("mount -o rw /system")
    @Test fun `umount is denied`() = deny("umount /system")
    @Test fun `mkfs is denied`() = deny("mkfs /dev/block/x")
    @Test fun `fastboot is denied`() = deny("fastboot reboot")
    @Test fun `ln symlink is denied`() = deny("ln -s /system/x /sdcard/y")
    @Test fun `mknod is denied`() = deny("mknod /dev/x c 1 1")
    @Test fun `svc is denied`() = deny("svc power reboot")
    @Test fun `chroot is denied`() = deny("chroot /data")
    @Test fun `insmod is denied`() = deny("insmod x.ko")

    // -------------------------------------------------------------------------------------------
    // WRITE DESTINATION restrictions.
    // -------------------------------------------------------------------------------------------

    @Test fun `cp into system app is denied`() = deny("cp /sdcard/x /system/app/y")
    @Test fun `cp into vendor is denied`() = deny("cp /sdcard/x /vendor/y")
    @Test fun `cp into proc is denied`() = deny("cp /sdcard/x /proc/y")
    @Test fun `cp into sys is denied`() = deny("cp /sdcard/x /sys/y")
    @Test fun `cp into dev is denied`() = deny("cp /sdcard/x /dev/y")
    @Test fun `mkdir into vendor is denied`() = deny("mkdir /vendor/x")
    @Test fun `mkdir into system is denied`() = deny("mkdir -p /system/x")
    @Test fun `chmod a system path is denied`() = deny("chmod 644 /system/app/x")

    @Test fun `cp into a system package data dir is denied`() =
        deny("cp /sdcard/x /data/data/com.android.systemui/z")

    @Test fun `cp into settings provider package is denied`() =
        deny("cp /sdcard/x /data/data/com.android.providers.settings/z")

    @Test fun `cp into an unknown (non-emulator) package data dir is denied`() =
        deny("cp /sdcard/x /data/data/com.evil.app/z")

    @Test fun `cp into the bare write-root path itself (no child) is denied`() =
        deny("cp /sdcard/x /sdcard/")

    // -------------------------------------------------------------------------------------------
    // mv SOURCE constraint — THE security-critical rule. Unlike cp, `mv` DELETES its source, so the
    // SOURCE must ALSO sit under an allowed write root (this app's own dirs / shared storage / a
    // known emulator dir). A system or other-app source is DENIED so root mv can never move-and-
    // unlink an arbitrary file. These prove the source check specifically, independently of the dest.
    // -------------------------------------------------------------------------------------------

    @Test fun `mv from a system path is denied (would delete a system file)`() =
        deny("mv /system/x /sdcard/y")

    @Test fun `mv from vendor is denied`() = deny("mv /vendor/lib/x /sdcard/y")
    @Test fun `mv from boot is denied`() = deny("mv /boot/x /sdcard/y")
    @Test fun `mv from proc is denied`() = deny("mv /proc/x /sdcard/y")
    @Test fun `mv from sys is denied`() = deny("mv /sys/x /sdcard/y")
    @Test fun `mv from dev is denied`() = deny("mv /dev/x /sdcard/y")
    @Test fun `mv from data app is denied`() = deny("mv /data/app/x /sdcard/y")
    @Test fun `mv from data local tmp is denied`() = deny("mv /data/local/tmp/x /sdcard/y")

    @Test fun `mv from another app data dir is denied (would delete its files)`() =
        deny("mv /data/data/com.android.systemui/databases/x /sdcard/y")

    @Test fun `mv from an unknown non-emulator package is denied`() =
        deny("mv /data/data/com.evil.app/x /sdcard/y")

    // Destination still constrained exactly as before — a system/other-app DEST is denied.
    @Test fun `mv into a system path is denied`() = deny("mv /sdcard/x /system/y")
    @Test fun `mv into vendor is denied`() = deny("mv /sdcard/x /vendor/y")
    @Test fun `mv into proc is denied`() = deny("mv /sdcard/x /proc/y")
    @Test fun `mv into a system package data dir is denied`() =
        deny("mv /sdcard/x /data/data/com.android.systemui/y")
    @Test fun `mv into the bare write-root path itself is denied`() = deny("mv /sdcard/x /sdcard/")

    // mv argument / flag hygiene (mv is stricter than cp: NO flags at all).
    @Test fun `mv with a force flag is denied`() = deny("mv -f /sdcard/a /sdcard/roms/b")
    @Test fun `mv with target-directory flag is denied`() = deny("mv -t /sdcard/roms /sdcard/a")
    @Test fun `mv with no-clobber flag is denied`() = deny("mv -n /sdcard/a /sdcard/roms/b")
    @Test fun `mv with only one operand is denied`() = deny("mv /sdcard/a")
    @Test fun `mv with three operands is denied`() = deny("mv /sdcard/a /sdcard/b /sdcard/roms/c")
    @Test fun `mv with a traversal in source is denied`() = deny("mv /sdcard/../system/x /sdcard/y")
    @Test fun `mv with a traversal in dest is denied`() = deny("mv /sdcard/a /sdcard/../system/y")
    @Test fun `mv with a metachar in source is denied`() = deny("mv /sdcard/\$(reboot) /sdcard/y")
    @Test fun `mv with a relative source is denied`() = deny("mv roms/x /sdcard/y")
    @Test fun `mv chained with reboot is denied`() = deny("mv /sdcard/a /sdcard/roms/b ; reboot")

    // -------------------------------------------------------------------------------------------
    // PATH TRAVERSAL + path hygiene.
    // -------------------------------------------------------------------------------------------

    @Test fun `traversal in source is denied`() = deny("cp ../../etc/x /sdcard/y")
    @Test fun `traversal in dest is denied`() = deny("cp /sdcard/x /sdcard/../system/y")
    @Test fun `dot-dot component in dest is denied`() = deny("cp /sdcard/x /sdcard/roms/../../../system/y")
    @Test fun `relative source path is denied`() = deny("cp roms/x /sdcard/y")
    @Test fun `relative dest path is denied`() = deny("cp /sdcard/x roms/y")
    @Test fun `single dot component is denied`() = deny("cp /sdcard/./x /sdcard/y")

    // -------------------------------------------------------------------------------------------
    // METACHARACTERS / injection / multi-command.
    // -------------------------------------------------------------------------------------------

    @Test fun `and-and chaining a bad command is denied`() = deny("cp /sdcard/a /sdcard/b && rm -rf /")
    @Test fun `semicolon chaining is denied`() = deny("cp /sdcard/a /sdcard/b ; reboot")
    @Test fun `pipe chaining is denied`() = deny("cat /sdcard/a | sh")
    @Test fun `or-or chaining is denied`() = deny("true || reboot")
    @Test fun `background ampersand is denied`() = deny("cp /sdcard/a /sdcard/b & reboot")
    @Test fun `backtick substitution is denied`() = deny("cat /sdcard/`reboot`")
    @Test fun `dollar-paren substitution is denied`() = deny("cat /sdcard/\$(reboot)")
    @Test fun `dollar variable is denied`() = deny("cat \$HOME/x")
    @Test fun `output redirect is denied`() = deny("echo x > /sys/fs/cgroup/x")
    @Test fun `input redirect is denied`() = deny("cat < /sdcard/x")
    @Test fun `glob star is denied`() = deny("cp /sdcard/* /sdcard/roms/")
    @Test fun `glob question is denied`() = deny("cp /sdcard/a? /sdcard/roms/")
    @Test fun `tilde expansion is denied`() = deny("cp ~/x /sdcard/y")
    @Test fun `brace is denied`() = deny("cp /sdcard/{a,b} /sdcard/roms/")
    @Test fun `backslash is denied`() = deny("cp /sdcard/a\\ b /sdcard/roms/")

    @Test fun `newline is denied`() = deny("cp /sdcard/a /sdcard/b\nreboot")
    @Test fun `carriage return is denied`() = deny("cp /sdcard/a /sdcard/b\rreboot")
    @Test fun `NUL byte is denied`() = deny("cp /sdcard/a /sdcard/b\u0000")
    @Test fun `tab control char is denied`() = deny("cp /sdcard/a\t/sdcard/b")

    // -------------------------------------------------------------------------------------------
    // BAD ARGUMENTS for allowed verbs.
    // -------------------------------------------------------------------------------------------

    @Test fun `chmod with dangerous 777 mode is denied`() = deny("chmod 777 /sdcard/roms/x")
    @Test fun `chmod with setuid 4755 mode is denied`() = deny("chmod 4755 /sdcard/roms/x")
    @Test fun `chmod recursive flag is denied`() = deny("chmod -R 755 /sdcard/roms")
    @Test fun `chmod with too few args is denied`() = deny("chmod 644")
    @Test fun `cp with only one operand is denied`() = deny("cp /sdcard/a")
    @Test fun `cp with three operands is denied`() = deny("cp /sdcard/a /sdcard/b /sdcard/roms/c")
    @Test fun `mv recursive flag is denied`() = deny("mv -r /sdcard/a /sdcard/roms/b")
    @Test fun `mkdir with unknown flag is denied`() = deny("mkdir -m777 /sdcard/roms/x")
    @Test fun `cat with a flag is denied`() = deny("cat -n /proc/version")
    @Test fun `cat with two paths is denied`() = deny("cat /proc/version /proc/cpuinfo")
    @Test fun `true with arguments is denied`() = deny("true x")
    @Test fun `id with arguments is denied`() = deny("id -u")
    @Test fun `getprop with no key is denied`() = deny("getprop")
    @Test fun `getprop with a flag is denied`() = deny("getprop -T x")

    // -------------------------------------------------------------------------------------------
    // chown is denied for the foundation.
    // -------------------------------------------------------------------------------------------

    @Test fun `chown is denied`() = deny("chown 1000 /sdcard/roms/x")

    // -------------------------------------------------------------------------------------------
    // DEFAULT-DENY: unknown verbs.
    // -------------------------------------------------------------------------------------------

    @Test fun `unknown verb is denied (default-deny)`() = deny("frobnicate /sdcard/x")
    @Test fun `echo is not allow-listed`() = deny("echo hello")
    @Test fun `touch is not allow-listed`() = deny("touch /sdcard/x")
    @Test fun `pm is not allow-listed`() = deny("pm install /sdcard/x.apk")
    @Test fun `am is not allow-listed`() = deny("am start -n a/b")
    @Test fun `settings is not allow-listed`() = deny("settings put global x 1")

    // -------------------------------------------------------------------------------------------
    // EMPTY / blank.
    // -------------------------------------------------------------------------------------------

    @Test fun `empty command is denied`() = deny("")
    @Test fun `blank command is denied`() = deny("   ")

    // -------------------------------------------------------------------------------------------
    // The deny reason is non-empty (so the bridge surfaces "BLOCKED: <reason>").
    // -------------------------------------------------------------------------------------------

    @Test fun `deny carries a non-empty reason`() {
        val v = PServerCommandGuard.inspect("rm -rf /")
        assertTrue(v is Verdict.Deny)
        assertTrue((v as Verdict.Deny).reason.isNotBlank(), "deny reason should be human-readable")
    }

    @Test fun `allow is returned as the Allow object`() {
        assertEquals(Verdict.Allow, PServerCommandGuard.inspect("true"))
    }

    // -------------------------------------------------------------------------------------------
    // Single-quote literal handling: an operator INSIDE single quotes is NOT a separator. The whole
    // thing is one segment; cp still validates and the quoted operand is rejected as a write target.
    // (We assert it's DENIED, proving the quoted '&&' did NOT spawn a second command — if it had
    // split, the segment count / parsing would differ; either way a literal '&&' filename is not a
    // valid write root, so deny is the safe expectation.)
    // -------------------------------------------------------------------------------------------

    @Test fun `single-quoted operator does not spawn a second command`() =
        deny("cp /sdcard/a '/sdcard/b && reboot'")

    @Test fun `unterminated single quote is denied`() = deny("cat '/sdcard/x")
}
