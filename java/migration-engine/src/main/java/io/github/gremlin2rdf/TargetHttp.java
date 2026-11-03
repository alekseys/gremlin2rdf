package io.github.gremlin2rdf;

import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;

final class TargetHttp {
    private TargetHttp() {}
    static boolean authorizationDenied(Exception failure) {
        if (failure instanceof HttpException update) return update.getStatusCode() == 401 || update.getStatusCode() == 403;
        if (failure instanceof QueryExceptionHTTP query) return query.getStatusCode() == 401 || query.getStatusCode() == 403;
        return false;
    }
    static boolean transientFailure(RuntimeException failure) {
        if (responseExceeded(failure)) return false;
        int status;
        if (failure instanceof HttpException update) status = update.getStatusCode();
        else if (failure instanceof QueryExceptionHTTP query) status = query.getStatusCode();
        else return false;
        return status == -1 || status == 408 || status == 429 || status >= 500 && status <= 599;
    }
    static boolean responseExceeded(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause())
            if (failure instanceof DeadlineHttpClient.ResponseLimit) return true;
        return false;
    }
}
