package dk.dbc.ess.service;

import dk.dbc.ess.service.response.EssResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.validation.constraints.NotNull;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import dk.dbc.sru.sruresponse.Record;
import dk.dbc.sru.sruresponse.Records;
import dk.dbc.sru.sruresponse.SearchRetrieveResponse;
import dk.dbc.sru.sruresponse.RecordXMLEscapingDefinition;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;


public class ExternalSearchService {
    private static final Logger log = LoggerFactory.getLogger(ExternalSearchService.class);
    private static final String controlField = "controlfield";
    private static final String zeroZeroOne = "001";

    Client client;
    Collection<String> knownBases;
    String sruTargetUrl;
    Formatting formatting;

    ExecutorService executorService;
    Timer timerSruRequest;
    Timer timerSruReadResponse;
    Timer timerRequest;
    int maxPageSize;

    public ExternalSearchService(Settings settings, MetricRegistry metrics, Client client) { // todo: metrics stuff
        this.client = client;

        this.knownBases = settings.getBases();
        this.sruTargetUrl = settings.getMetaProxyUrl();
        this.maxPageSize = settings.getMaxPageSize();

        this.executorService = Executors.newCachedThreadPool();
        this.formatting = new Formatting(settings, metrics, client);

        this.timerSruRequest = makeTimer(metrics, "sruRequest");
        this.timerSruReadResponse = makeTimer(metrics, "sruReadResponse");
        this.timerRequest = makeTimer(metrics, "Request");
    }

    private Timer makeTimer(MetricRegistry metricRegistry, String name) {
        return metricRegistry.timer(getClass().getCanonicalName() + "#" + name);
    }

    @GET
    @Path("rpn/")
    public Response requestRPN(@QueryParam("base") @NotNull String base,
                               @QueryParam("query") @NotNull String query,
                               @QueryParam("start") Integer start,
                               @QueryParam("rows") Integer rows,
                               @QueryParam("format") @NotNull String format,
                               @QueryParam("trackingId") String trackingId) {
        return processRequest(base, query, start, rows, format, trackingId, true);
    }

    @GET
    public Response requestCQL(@QueryParam("base") @NotNull String base,
                               @QueryParam("query") @NotNull String query,
                               @QueryParam("start") Integer start,
                               @QueryParam("rows") Integer rows,
                               @QueryParam("format") @NotNull String format,
                               @QueryParam("trackingId") String trackingId) {
        return processRequest(base, query, start, rows, format, trackingId, false);
    }

    private Response processRequest(String base, String query, Integer start, Integer rows, String format, String trackingId, boolean isRPN) {
        if (start == null) {
            start = 1;
        }
        if (rows == null || rows >= maxPageSize) {
            rows = maxPageSize;
        }
        if (trackingId == null || trackingId.isEmpty()) {
            trackingId = UUID.randomUUID().toString();
        }
        if (!knownBases.contains(base)) {
            return serverError("Unknown base requested");
        }
        log.info("base: " + base + "; format: " + format +
                "; start: " + start + "; rows: " + rows +
                "; trackingId: " + trackingId + "; query: " + query + "; type: " + (isRPN ? "rpn" : "cql"));

        try (Timer.Context timer = timerRequest.time()) {
            String queryParam = isRPN ? "x-pqueryy" : "query";
            Response response = requestSru(base, queryParam, query, start, rows);

            if (!response.getStatusInfo().equals(Response.Status.OK)) {
                log.error("Search failed with http code: " + response.getStatusInfo() + " for: " + trackingId);
                return serverError("Internal Server Error");
            }

            SearchRetrieveResponse sru = responseSru(response);

            return buildResponse(sru, format, "base: ", trackingId);

        } catch (Exception ex) {
            log.error("Error Processing Response: " + ex.getMessage() + " for: " + trackingId);
            log.debug("Error Processing Response:", ex);
        }
        return serverError("Internal Server Error");
    }

    private Response buildResponse(SearchRetrieveResponse sru, String output, String idPrefix, String trackingId)
        throws InterruptedException, ExecutionException {
        final String controlField = "controlfield";
        final String zeroZeroOne = "001";

        long hits = sru.getNumberOfRecords();
        EssResponse essResponse = new EssResponse();
        essResponse.hits = hits;
        essResponse.records = new ArrayList<>();
        essResponse.trackingId = trackingId;
        Records recs = sru.getRecords();
        if (recs != null) {
            List<Record> recordList = recs.getRecords();
            List<Future<Element>> futures = new ArrayList<>(recordList.size());
            for (Record record : recordList) {
                Future<Element> future;
                RecordXMLEscapingDefinition esc = record.getRecordXMLEscaping();
                log.debug("esc: " + esc);
                if (esc != RecordXMLEscapingDefinition.XML) {
                    log.error("Expected xml escaped record in response, got: " + esc);
                    future = executorService.submit(formatting.formattingError("Internal Server Error"));
                }
                else {
                    List<Object> content = record.getRecordData().getContent();
                    if (content.size() == 1) {
                        Object obj = content.get(0);
                        if (obj instanceof  Element) {
                            String remoteId = null;
                            Element e = (Element) obj;
                            for (Node child = e.getFirstChild(); child != null; child = child.getNextSibling()) {
                                if (child.getNodeType() == Node.ELEMENT_NODE && controlField.equals(child.getLocalName())) {
                                    NamedNodeMap attributes = child.getAttributes();
                                    Node tag = attributes.getNamedItem("tag");
                                    if (tag != null && zeroZeroOne.equals(tag.getNodeValue())) {
                                        Node id = child.getFirstChild();
                                        if (id.getNodeType() == Node.TEXT_NODE) {
                                            remoteId = idPrefix + id.getNodeValue();
                                        }
                                        break;
                                    }
                                }
                            }
                            if (remoteId == null) {
                                remoteId = idPrefix + UUID.randomUUID().toString();
                            }
                            future = executorService.submit(formatting.formattingCall(e, output, remoteId, trackingId));
                        }
                        else {
                            log.error("Not of type XML: " + obj.getClass().getCanonicalName() + ". This should not happen.");
                            future = executorService.submit(formatting.formattingError("Internal Server Error"));
                        }
                    }
                    else {
                        log.error("Expected 1 record in response, but got " + content.size());
                        log.debug("Types: ");
                        for (Object o : content) {
                            log.debug(o.getClass().getCanonicalName());
                        }
                        future = executorService.submit(formatting.formattingError("Internal Server Error"));
                    }
                }
                futures.add(future);
            }
            for (Future<Element> f : futures) {
                essResponse.records.add(f.get());
            }
        }
        return Response.ok(essResponse, MediaType.APPLICATION_XML_TYPE).build();
    }

    private Response requestSru(String base, String queryParam, String query, Integer start, Integer stepValue)
            throws Exception {
        Invocation invocation = client
                .target(sruTargetUrl)
                .path(base)
                .queryParam(queryParam, query)
                .queryParam("startRecord", start)
                .queryParam("maximumRecords", stepValue)
                .request(MediaType.APPLICATION_XML_TYPE)
                .buildGet();
        return timerSruRequest.time(() -> invocation.invoke());
    }

    private SearchRetrieveResponse responseSru(Response response) throws  Exception {
        return  timerSruReadResponse.time(() -> response.readEntity(SearchRetrieveResponse.class));
    }

    private Response serverError(String message) {
        return Response.serverError().entity(message).build();
    }

}
