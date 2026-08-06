package pl.najem.pm.adapter.rest;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the portfolio endpoints accept and answer with, as JSON sees it.
 *
 * <p>Wire types live in the adapter and never in the domain: external JSON is bound into these and
 * translated, so a rename in {@code Property} or {@code Unit} is not a breaking API change and a
 * field an attacker invents cannot reach an aggregate.
 *
 * <p>Gathered here rather than nested in the controller so that the wire surface of these seven
 * endpoints can be read in one place — the shape a client depends on is a thing in its own right,
 * and it was previously visible only by scrolling past the handlers that use it.
 */
final class PortfolioWire {

    private PortfolioWire() {
    }

    record OwnerDto(UUID contactId, BigDecimal sharePercent) {}

    record CreatePropertyRequest(String address, List<OwnerDto> owners) {}

    record AddUnitRequest(String name, BigDecimal baseRent) {}

    record BaseRentRequest(BigDecimal baseRent) {}

    record ReasonRequest(String reason) {}

    /**
     * What a create answers with. A named type rather than {@code Map.of("propertyId", id)}: the map
     * put the field name in a string literal, where nothing checked it and no reader of the method
     * signature could see it.
     *
     * <p>The JSON is unchanged — {@code {"propertyId":"…"}} either way — which is the point. The
     * e2e suites read {@code .path("propertyId")} and stayed green without being touched.
     */
    record PropertyCreated(UUID propertyId) {}

    record UnitAdded(UUID unitId) {}

    /**
     * The details a manager can set on a unit, with the one the domain understands given a name.
     *
     * <p>The rest is deliberately still open. {@code UnitDetailsUpdated} stores this map in the
     * event stream and {@code UnitTest} pins the catch-all — a description alongside a listing
     * reference is carried and kept, and narrowing this to a fixed record would silently drop
     * whatever a manager had typed. So {@code listingRef} is a field, everything else arrives
     * through {@link JsonAnySetter}, and the wire shape is the flat object it always was.
     */
    static final class UnitDetailsRequest {

        private final Map<String, String> details = new LinkedHashMap<>();

        public void setListingRef(String listingRef) {
            put("listingRef", listingRef);
        }

        public String getListingRef() {
            return details.get("listingRef");
        }

        @JsonAnySetter
        public void put(String key, String value) {
            if (value != null) {
                details.put(key, value);
            }
        }

        @JsonAnyGetter
        public Map<String, String> details() {
            return Map.copyOf(details);
        }
    }
}
