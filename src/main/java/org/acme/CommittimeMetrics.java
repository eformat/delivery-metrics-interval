package org.acme;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.util.ArrayList;

@Path("/cd-metrics")
public class CommittimeMetrics {

    @Inject
    Vertx vertx;

    @Inject
    MeterRegistry registry;

    @ConfigProperty(name = "prometheus.query.url")
    String prometheusURL;

    @ConfigProperty(name = "auth.header")
    String authHeader;

    private final String QUERY = "?query=sort(max%20by%20(app,commit,image_sha)(commit_timestamp))";

    private Uni<JsonObject> fetchRaw() {
        WebClientOptions options = new WebClientOptions()
                .setTrustAll(true)
                .setVerifyHost(false);
        return WebClient.create(vertx, options)
                .getAbs(prometheusURL + QUERY)
                .putHeader("Authorization", "Bearer " + authHeader)
                .send()
                .onItem().transform(HttpResponse::bodyAsJsonObject);
    }

    class Stat {
        int commitTime;
        String app;
        String commit;
        String image;

        Stat(JsonObject stat) {
            commitTime = Integer.valueOf(stat.getJsonArray("value").getString(1)).intValue();
            app = stat.getJsonObject("metric").getString("app");
            commit = stat.getJsonObject("metric").getString("commit");
            image = stat.getJsonObject("metric").getString("image_sha");
        }
    }

    class Stats {
        ArrayList stats = new ArrayList<Stat>();
        ArrayList cTime = new ArrayList<Integer>();

        void addStat(Stat stat) {
            stats.add(stat);
            cTime.add(stat.commitTime);
        }

        ArrayList<Integer> delta() {
            ArrayList deltas = new ArrayList<Integer>();
            for (int i = 0; i < cTime.size() - 1; i++) {
                //System.out.println((int)cTime.get(i+1) + ":" + (int)cTime.get(i));
                deltas.add((int) cTime.get(i + 1) - (int) cTime.get(i));
            }
            return deltas;
        }

        // average of the deltas in [sec]
        double avg() {
            ArrayList<Integer> deltas = delta();
            return deltas.stream().mapToInt(d -> (int) d).average().orElse(0.0);
        }

        // stdev of the deltas in [sec]
        double stdev () {
            ArrayList<Integer> deltas = delta();
            double mean = deltas.stream().mapToInt(d -> (int) d).average().orElse(0.0);
            double tmp = 0;
            for (int i = 0; i < deltas.size(); i++)  {
                int val = (int) deltas.get(i);
                //System.out.println(Math.abs(val - mean));
                double squrDiffToMean = Math.pow((val - mean), 2);
                tmp += squrDiffToMean;
            }
            double meanOfDiffs = (double) tmp / (double) (deltas.size());
            return Math.sqrt(meanOfDiffs);
        }
    }

    @GET
    @Path("commit-interval")
    public JsonObject metrics() {
        Stats calcs = new Stats();
        fetchRaw().invoke(
                req -> req.getJsonObject("data").getJsonArray("result").forEach(
                        item -> calcs.addStat(new Stat((JsonObject) item))
                )
        ).await().indefinitely();
        JsonObject ret = new JsonObject();
        // metrics in [hr]
        ret.put("avg", calcs.avg() / (60 * 60));
        ret.put("stdev", calcs.stdev() / (60 * 60));
        // FIXME app names
        registry.gauge("cd:commit_interval:avg", Tags.of("app", "pet-battle-api"), calcs.avg() / (60 * 60));
        registry.gauge("cd:commit_interval:stdev", Tags.of("app", "pet-battle-api"), calcs.stdev() / (60 * 60));
        return ret;
    }
}
