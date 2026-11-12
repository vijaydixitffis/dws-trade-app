package com.dws.trade.query;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.List;
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

    public void save(EventDocument event) {
        mongo.save(event);
    }

    public void save(RejectionDocument rejection) {
        mongo.save(rejection);
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
