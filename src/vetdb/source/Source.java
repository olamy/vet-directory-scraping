package vetdb.source;

import java.util.function.Consumer;

import vetdb.http.Fetcher;
import vetdb.model.SourceRecord;

/** One directory site we scrape. */
public interface Source {

    String name();

    /** Host name, used to set the polite delay between requests. */
    String host();

    /** Minimum gap between two requests to this site. */
    long delayMillis();

    /**
     * Finds every listing on the site, fetches it and hands each parsed record to the sink.
     * Records are not normalised yet. A limit above zero stops after that many listings.
     */
    void run(Fetcher fetcher, int limit, Consumer<SourceRecord> sink);
}
