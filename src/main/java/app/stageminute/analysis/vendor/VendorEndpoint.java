package app.stageminute.analysis.vendor;

import app.stageminute.platform.VendorHttp;
import java.util.Objects;

/**
 * Where a real client calls and with what: the base address (https, or plain http to this machine for a test, checked
 * by {@link VendorHttp#requireSafeBase}), the key and the pinned model name. The key never prints.
 *
 * @param baseUrl the vendor's address without a trailing slash
 * @param apiKey the vendor key
 * @param model the pinned model name
 */
record VendorEndpoint(String baseUrl, String apiKey, String model) {

    VendorEndpoint {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(model, "model");
        if (apiKey.isBlank() || model.isBlank()) {
            throw new IllegalArgumentException("a vendor needs a key and a model");
        }
        baseUrl = VendorHttp.requireSafeBase("vendor base address", baseUrl);
    }

    @Override
    public String toString() {
        return "VendorEndpoint[" + model + "]";
    }
}
