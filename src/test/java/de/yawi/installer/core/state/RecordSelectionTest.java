package de.yawi.installer.core.state;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** E14-S02: a previous installation's answers, checked against the manifest that is about to run. */
class RecordSelectionTest {

    @Test
    void keepsKnownComponentsReportsDroppedOnesAndCarriesTheInputs() throws Exception {
        InstallManifest manifest = TestManifests.full();
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("serverPort", "50000");
        inputs.put("mode", "internet");
        InstallationRecord record = new InstallationRecord("your-product", "2.4.0", Instant.EPOCH,
                Path.of("/tmp/x"), List.of("core", "server", "retired-addon"), inputs);

        RecordSelection selection = RecordSelection.from(manifest, record);

        assertEquals(List.of("core", "server"), selection.components());
        assertEquals(List.of("retired-addon"), selection.dropped());
        assertEquals(Map.of("serverPort", "50000", "mode", "internet"), selection.inputs());
    }
}
