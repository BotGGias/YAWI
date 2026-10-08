package de.yawi.installer.core.manifest;

import java.util.Objects;
import java.util.Optional;

/**
 * The {@code <product>} header.
 *
 * @param homepage optional URL shown on the welcome/finish page, may be null
 * @param icon     optional {@code classpath:} or file reference, may be null
 */
public record Product(String id, String version, String name, String vendor, String homepage, String icon) {

    public Product {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(name, "name");
    }

    public Optional<String> vendorOrEmpty() {
        return Optional.ofNullable(vendor);
    }

    public Optional<String> homepageOrEmpty() {
        return Optional.ofNullable(homepage);
    }

    public Optional<String> iconOrEmpty() {
        return Optional.ofNullable(icon);
    }
}
