package vetdb.model;

import java.util.Map;

/**
 * Result of looking a clinic up in ABR or Google, keyed by the clinic's anchor member
 * so it survives a re-merge.
 *
 * @param kind      "abr" or "google"
 * @param memberKey anchor source record key, e.g. "ratemyvet:some-slug"
 * @param status    MATCHED, AMBIGUOUS, NOT_FOUND or ERROR
 * @param values    fields to copy onto the clinic when matched
 */
public record Enrichment(String kind, String memberKey, String status, Map<Field, String> values) {

    public boolean matched() {
        return "MATCHED".equals(status);
    }
}
