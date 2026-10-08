module de.yawi.installer {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.xml;
    requires java.net.http;

    requires org.slf4j;
    // Laufzeit-Binding fuer SLF4J. Ohne dieses 'requires' liegt logback nicht im
    // Modulgraph, SLF4J faellt still auf NOP zurueck und es wird nichts geloggt.
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;    // LogSetup: Rekonfiguration (JoranException)

    // Von der vendored bt-Bibliothek benoetigt:
    requires com.google.common;
    requires com.google.guice;   // Guice 6, Automatic-Module-Name
    requires javax.inject;       // nur bt/net/portmapping/impl/PortMappingInitializer
    // Guice 6 laedt jakarta.inject.Provider beim Erzeugen des Injectors; als
    // echtes Modul wird es nur aufgeloest, wenn jemand es verlangt (E08).
    requires jakarta.inject;
    requires org.yaml.snakeyaml; // bt/bencoding/model/YamlBEObjectModelLoader

    exports de.yawi.installer;
    exports de.yawi.installer.cli;
    exports de.yawi.installer.core.answers;
    exports de.yawi.installer.core.download;
    exports de.yawi.installer.core.elevation;
    exports de.yawi.installer.core.engine;
    exports de.yawi.installer.core.engine.step;
    exports de.yawi.installer.core.error;
    exports de.yawi.installer.core.i18n;
    exports de.yawi.installer.core.integrity;
    exports de.yawi.installer.core.integration;
    exports de.yawi.installer.core.manifest;
    exports de.yawi.installer.core.platform;
    exports de.yawi.installer.core.state;
    exports de.yawi.installer.core.xml;

    exports de.yawi.installer.ui;
    exports de.yawi.installer.ui.i18n;
    exports de.yawi.installer.ui.page;

    opens de.yawi.installer.ui to javafx.fxml;
    opens de.yawi.installer.ui.page to javafx.fxml;

    // The installer's own bt bindings (E08) are injected the same way.
    opens de.yawi.installer.core.download.torrent to com.google.guice;
    // Guice builds bt's object graph by reflection (E08-S01-T06): it needs
    // every bt package open, not only those with @Inject - JIT bindings use
    // plain constructors too. Opened wholesale so a bt update cannot break it.
    opens bt to com.google.guice;
    opens bt.bencoding to com.google.guice;
    opens bt.bencoding.model to com.google.guice;
    opens bt.bencoding.model.rule to com.google.guice;
    opens bt.bencoding.serializers to com.google.guice;
    opens bt.bencoding.types to com.google.guice;
    opens bt.data to com.google.guice;
    opens bt.data.digest to com.google.guice;
    opens bt.data.file to com.google.guice;
    opens bt.data.range to com.google.guice;
    opens bt.event to com.google.guice;
    opens bt.magnet to com.google.guice;
    opens bt.metainfo to com.google.guice;
    opens bt.module to com.google.guice;
    opens bt.net to com.google.guice;
    opens bt.net.buffer to com.google.guice;
    opens bt.net.crypto to com.google.guice;
    opens bt.net.extended to com.google.guice;
    opens bt.net.pipeline to com.google.guice;
    opens bt.net.portmapping to com.google.guice;
    opens bt.net.portmapping.impl to com.google.guice;
    opens bt.peer to com.google.guice;
    opens bt.peerexchange to com.google.guice;
    opens bt.peer.lan to com.google.guice;
    opens bt.processor to com.google.guice;
    opens bt.processor.listener to com.google.guice;
    opens bt.processor.magnet to com.google.guice;
    opens bt.processor.torrent to com.google.guice;
    opens bt.protocol to com.google.guice;
    opens bt.protocol.crypto to com.google.guice;
    opens bt.protocol.extended to com.google.guice;
    opens bt.protocol.handler to com.google.guice;
    opens bt.runtime to com.google.guice;
    opens bt.service to com.google.guice;
    opens bt.torrent to com.google.guice;
    opens bt.torrent.annotation to com.google.guice;
    opens bt.torrent.callbacks to com.google.guice;
    opens bt.torrent.compiler to com.google.guice;
    opens bt.torrent.data to com.google.guice;
    opens bt.torrent.fileselector to com.google.guice;
    opens bt.torrent.maker to com.google.guice;
    opens bt.torrent.messaging to com.google.guice;
    opens bt.torrent.selector to com.google.guice;
    opens bt.tracker to com.google.guice;
    opens bt.tracker.http to com.google.guice;
    opens bt.tracker.http.urlencoding to com.google.guice;
    opens bt.tracker.udp to com.google.guice;
}
