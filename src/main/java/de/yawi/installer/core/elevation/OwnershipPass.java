package de.yawi.installer.core.elevation;

import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.state.InstallationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Gives back to the calling user what the elevated process created on the
 * user's behalf: everything the record names under the user's
 * home or temp directory (desktop entries, menu entries, icons), the menu
 * cache {@code update-desktop-database} wrote, and - for a user-writable
 * destination - everything under the destination. Never the destination
 * of a system-wide installation. POSIX only, best effort: a path that
 * cannot be changed is logged, not fatal.
 */
final class OwnershipPass {

    private static final Logger LOG = LoggerFactory.getLogger(OwnershipPass.class);

    private OwnershipPass() {
    }

    static void apply(ElevationPlan plan, InstallationRecord record, Platform platform, Set<Path> extra) {
        if (plan.ownerUser().isEmpty() || !HandoverDir.posix() || !platform.isElevated()) {
            return;
        }
        UserPrincipalLookupService lookup = plan.destination().getFileSystem().getUserPrincipalLookupService();
        UserPrincipal user;
        Optional<GroupPrincipal> group;
        try {
            user = lookup.lookupPrincipalByName(plan.ownerUser().get());
            group = plan.ownerGroup().isPresent()
                    ? Optional.of(lookup.lookupPrincipalByGroupName(plan.ownerGroup().get())) : Optional.empty();
        } catch (IOException | UnsupportedOperationException e) {
            LOG.warn("Cannot look up user {}: {} - created files stay root's", plan.ownerUser().get(), e.toString());
            return;
        }
        Set<Path> candidates = new LinkedHashSet<>();
        for (InstallationRecord.Entry entry : record.entries()) {
            switch (entry) {
                case InstallationRecord.CreatedFile f -> candidates.add(record.resolve(f.path()));
                case InstallationRecord.CreatedDirectory d -> candidates.add(record.resolve(d.path()));
                case InstallationRecord.ReplacedFile r -> {
                    candidates.add(record.resolve(r.path()));
                    candidates.add(record.resolve(r.backup()));
                }
                case InstallationRecord.ModeChanged m -> candidates.add(record.resolve(m.path()));
                default -> {
                }
            }
        }
        candidates.add(platform.applicationMenuDir(false).resolve("mimeinfo.cache"));
        candidates.addAll(extra);
        if (plan.chownDestination()) {
            candidates.add(plan.destination());
            candidates.add(plan.destination().resolve(".installer"));
        }
        int changed = 0;
        for (Path path : candidates) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !belongsToUser(path, plan, platform)) {
                continue;
            }
            try {
                PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (view == null) {
                    continue;
                }
                view.setOwner(user);
                if (group.isPresent()) {
                    view.setGroup(group.get());
                }
                changed++;
            } catch (IOException e) {
                LOG.warn("Cannot hand {} back to {}: {}", path, plan.ownerUser().get(), e.toString());
            }
        }
        LOG.info("Ownership pass: {} path(s) now belong to {}", changed, plan.ownerUser().get());
    }

    /** Under the caller's home or temp directory, or under a destination the caller can write anyway. */
    static boolean belongsToUser(Path path, ElevationPlan plan, Platform platform) {
        Path absolute = path.toAbsolutePath().normalize();
        if (plan.chownDestination() && absolute.startsWith(plan.destination())) {
            return true;
        }
        return absolute.startsWith(platform.homeDir().toAbsolutePath().normalize())
                || absolute.startsWith(platform.tempDir().toAbsolutePath().normalize());
    }
}
