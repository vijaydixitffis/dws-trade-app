package com.dws.trade.query;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** The history of store changes and rejections kept in MongoDB. */
@Repository
public class History {

    private final MongoTemplate mongo;

    public History(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Stores the event unless one with the same id exists. After a crash the processor may
     * describe a reprocessed record differently (REPLACED instead of ACCEPTED); the history keeps
     * what was said first.
     *
     * @return true if the event was new
     */
    public boolean insert(EventDocument event) {
        try {
            mongo.insert(event);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** @return true if the rejection was new */
    public boolean insert(RejectionDocument rejection) {
        try {
            mongo.insert(rejection);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<EventDocument> latestEvents(int limit) {
        return mongo.find(newestFirst(limit), EventDocument.class);
    }

    public List<RejectionDocument> latestRejections(int limit) {
        return mongo.find(newestFirst(limit), RejectionDocument.class);
    }

    public long countEvents(String type) {
        return mongo.count(new Query(where("type").is(type)), EventDocument.class);
    }

    public long countRejections(String reason) {
        return mongo.count(new Query(where("reason").is(reason)), RejectionDocument.class);
    }

    private static Query newestFirst(int limit) {
        return new Query().with(Sort.by(Sort.Direction.DESC, "at")).limit(limit);
    }
}
