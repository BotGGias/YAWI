package de.yawi.installer.core.state;

import de.yawi.installer.core.manifest.InstallManifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a previous installation chose, filtered against the current manifest:
 * the answers an update or the maintenance page starts from. Components the
 * new manifest no longer has are reported in {@code dropped} rather than
 * silently lost; inputs come back as recorded (secrets were never written,
 * so those are simply absent).
 */
public record RecordSelection(List<String> components, List<String> dropped, Map<String, String> inputs) {

    public RecordSelection {
        components = List.copyOf(components);
        dropped = List.copyOf(dropped);
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
    }

    public static RecordSelection from(InstallManifest manifest, InstallationRecord record) {
        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (String id : record.components()) {
            if (manifest.component(id).isPresent()) {
                kept.add(id);
            } else {
                dropped.add(id);
            }
        }
        return new RecordSelection(kept, dropped, record.inputs());
    }
}
