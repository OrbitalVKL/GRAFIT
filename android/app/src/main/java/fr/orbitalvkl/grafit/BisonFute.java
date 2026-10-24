package fr.orbitalvkl.grafit;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Évènements routiers Bison Futé (données ouvertes DATEX II, réseau national non concédé, Licence Ouverte Etalab) :
 * fermetures, voies fermées, travaux, déviations, conditions météo… Lecture en flux, ne garde que les
 * enregistrements dont un point tombe dans l'emprise demandée ; renvoie {bf: {ok, ev: [{type, sub, road, txt, pts}]}}.
 */
final class BisonFute {
    static final String URL_OPEN = "https://tipi.bison-fute.gouv.fr/bison-fute-ouvert/publicationsDIR/Evenementiel-DIR/grt/RRN/content.xml";
    static final Set<String> SUBS = new HashSet<>(Arrays.asList("roadOrCarriagewayOrLaneManagementType", "roadMaintenanceType", "accidentType",
        "abnormalTrafficType", "vehicleObstructionType", "obstructionType", "poorEnvironmentType", "weatherRelatedRoadConditionType",
        "reroutingManagementType", "environmentalObstructionType", "constructionWorkType", "animalPresenceType", "infrastructureDamageType"));

    static String local(String n) { int i = n == null ? -1 : n.indexOf(':'); return i < 0 ? n : n.substring(i + 1); }

    static void fetch(MainActivity act, double[] box) {
        JSONObject r = new JSONObject();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(URL_OPEN).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(30000);
            JSONArray out = new JSONArray();
            try (InputStream in = c.getInputStream()) {
                XmlPullParser x = Xml.newPullParser();
                x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
                x.setInput(in, null);
                String type = null, sub = null, road = null, txt = null, status = null, pendingComment = null;
                List<double[]> pts = new ArrayList<>();
                Double lat = null;
                StringBuilder text = new StringBuilder();
                boolean inRec = false;
                for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        String n = local(x.getName());
                        if (n.equals("situationRecord")) {
                            inRec = true; type = local(x.getAttributeValue(null, "xsi:type")); sub = road = txt = status = pendingComment = null; pts.clear(); lat = null;
                        }
                        text.setLength(0);
                    } else if (ev == XmlPullParser.TEXT) {
                        text.append(x.getText());
                    } else if (ev == XmlPullParser.END_TAG) {
                        String n = local(x.getName()), v = text.toString().trim();
                        text.setLength(0);
                        if (!inRec) continue;
                        switch (n) {
                            case "latitude": try { lat = Double.parseDouble(v); } catch (Exception e) { lat = null; } break;
                            case "longitude":
                                if (lat != null && pts.size() < 6) { try { pts.add(new double[]{lat, Double.parseDouble(v)}); } catch (Exception e) { /* */ } }
                                lat = null; break;
                            case "roadNumber": if (road == null && !v.isEmpty()) road = v; break;
                            case "value": if (!v.isEmpty()) pendingComment = v; break;
                            case "commentType": if ("description".equals(v) && txt == null && pendingComment != null) txt = pendingComment; pendingComment = null; break;
                            case "validityStatus": status = v; break;
                            case "situationRecord": {
                                inRec = false;
                                if ("suspended".equals(status)) break;
                                boolean in = false;
                                for (double[] p : pts) if (p[0] >= box[0] && p[0] <= box[2] && p[1] >= box[1] && p[1] <= box[3]) { in = true; break; }
                                if (in && out.length() < 80) {
                                    JSONObject e = new JSONObject();
                                    e.put("type", type == null ? "" : type); e.put("sub", sub == null ? "" : sub); e.put("road", road == null ? "" : road.replaceAll("^([A-Z])0+", "$1"));
                                    e.put("txt", txt == null ? "" : (txt.length() > 90 ? txt.substring(0, 90) : txt).toUpperCase());
                                    JSONArray pa = new JSONArray(); for (double[] p : pts) { JSONArray q = new JSONArray(); q.put(p[0]); q.put(p[1]); pa.put(q); }
                                    e.put("pts", pa); out.put(e);
                                }
                                break;
                            }
                            default: if (sub == null && SUBS.contains(n) && !v.isEmpty()) sub = v;
                        }
                    }
                }
            }
            r.put("ok", true); r.put("ev", out);
        } catch (Exception e) { try { r.put("ok", false); r.put("err", String.valueOf(e.getMessage())); } catch (Exception x) { /* */ } }
        try { JSONObject d = new JSONObject(); d.put("bf", r); act.push(d); } catch (Exception e) { /* */ }
    }
}
