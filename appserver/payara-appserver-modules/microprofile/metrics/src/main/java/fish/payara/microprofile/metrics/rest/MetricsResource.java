/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 *    Copyright (c) [2018-2019] Payara Foundation and/or its affiliates. All rights reserved.
 *
 *     The contents of this file are subject to the terms of either the GNU
 *     General Public License Version 2 only ("GPL") or the Common Development
 *     and Distribution License("CDDL") (collectively, the "License").  You
 *     may not use this file except in compliance with the License.  You can
 *     obtain a copy of the License at
 *     https://github.com/payara/Payara/blob/master/LICENSE.txt
 *     See the License for the specific
 *     language governing permissions and limitations under the License.
 *
 *     When distributing the software, include this License Header Notice in each
 *     file and include the License file at glassfish/legal/LICENSE.txt.
 *
 *     GPL Classpath Exception:
 *     The Payara Foundation designates this particular file as subject to the "Classpath"
 *     exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 *     file that accompanied this code.
 *
 *     Modifications:
 *     If applicable, add the following below the License Header, with the fields
 *     enclosed by brackets [] replaced by your own identifying information:
 *     "Portions Copyright [year] [name of copyright owner]"
 *
 *     Contributor(s):
 *     If you wish your version of this file to be governed by only the CDDL or
 *     only the GPL Version 2, indicate your decision by adding "[Contributor]
 *     elects to include this software in this distribution under the [CDDL or GPL
 *     Version 2] license."  If you don't indicate a single choice of license, a
 *     recipient has the option to distribute your version of this file under
 *     either the CDDL, the GPL Version 2 or to extend the choice of license to
 *     its licensees as provided above.  However, if you add GPL Version 2 code
 *     and therefore, elected the GPL Version 2 license, then the option applies
 *     only if the new code is made subject to such option by the copyright
 *     holder.
 */

package fish.payara.microprofile.metrics.rest;

import fish.payara.microprofile.metrics.MetricsService;
import fish.payara.microprofile.metrics.exception.NoSuchMetricException;
import fish.payara.microprofile.metrics.exception.NoSuchRegistryException;
import fish.payara.microprofile.metrics.writer.JsonMetadataWriter;
import fish.payara.microprofile.metrics.writer.JsonMetricWriter;
import fish.payara.microprofile.metrics.writer.MetricsWriter;
import fish.payara.microprofile.metrics.writer.PrometheusWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;

import static fish.payara.microprofile.Constants.EMPTY_STRING;
import static java.nio.charset.StandardCharsets.UTF_8;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static javax.servlet.http.HttpServletResponse.SC_NOT_ACCEPTABLE;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.ws.rs.HttpMethod.GET;
import static javax.ws.rs.HttpMethod.OPTIONS;
import static javax.ws.rs.core.HttpHeaders.ACCEPT;
import javax.ws.rs.core.MediaType;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static org.eclipse.microprofile.metrics.MetricRegistry.Type.APPLICATION;
import static org.eclipse.microprofile.metrics.MetricRegistry.Type.BASE;
import static org.eclipse.microprofile.metrics.MetricRegistry.Type.VENDOR;

import org.glassfish.internal.api.Globals;

public class MetricsResource extends HttpServlet {

    private static final String APPLICATION_WILDCARD = "application/*";

    private static final List<String> REGISTRY_NAMES = Arrays.asList(
            BASE.getName(), VENDOR.getName(), APPLICATION.getName()
    );

    /**
     * Processes requests for both HTTP <code>GET</code> and <code>OPTIONS</code>
     * methods.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        MetricsService metricsService = Globals.getDefaultBaseServiceLocator().getService(MetricsService.class);

        if (!metricsService.isEnabled()) {
            response.sendError(SC_FORBIDDEN, "MicroProfile Metrics Service is disabled");
            return;
        }
        metricsService.reregisterMetadataConfig();
        MetricsRequest metricsRequest = new MetricsRequest(request);
        try {
            if (metricsRequest.isRegistryRequested()
                    && !REGISTRY_NAMES.contains(metricsRequest.getRegistryName())) {
                throw new NoSuchRegistryException(metricsRequest.getRegistryName());
            }
            MetricsWriter outputWriter = getOutputWriter(request, response);
            if (outputWriter != null) {
                setContentType(outputWriter, response);
                if (metricsRequest.isRegistryRequested() && metricsRequest.isMetricRequested()) {
                    outputWriter.write(metricsRequest.getRegistryName(), metricsRequest.getMetricName());
                } else if (metricsRequest.isRegistryRequested()) {
                    outputWriter.write(metricsRequest.getRegistryName());
                } else {
                    outputWriter.write();
                }
            }
        } catch (NoSuchRegistryException ex) {
            response.sendError(
                    SC_NOT_FOUND,
                    String.format("[%s] registry not found", metricsRequest.getRegistryName()));
        } catch (NoSuchMetricException ex) {
            response.sendError(
                    SC_NOT_FOUND,
                    String.format("[%s] metric not found", metricsRequest.getMetricName()));
        }
    }

    private void setContentType(MetricsWriter outputWriter, HttpServletResponse response) {
        if (outputWriter instanceof JsonMetricWriter) {
            response.setContentType(APPLICATION_JSON);
        } else if (outputWriter instanceof JsonMetadataWriter) {
            response.setContentType(APPLICATION_JSON);
        } else {
            response.setContentType(TEXT_PLAIN);
        }
        response.setCharacterEncoding(UTF_8.name());
    }

    private MetricsWriter getOutputWriter(HttpServletRequest request, HttpServletResponse response) throws IOException {
        MetricsWriter outputWriter = null;
        String method = request.getMethod();
        Writer writer = response.getWriter();

        String accept = request.getHeader(ACCEPT);
        if (accept == null) {
            accept = TEXT_PLAIN;
        }

        switch (method) {
            case GET:
                //application/json;q=0.1,text/plain;q=0.9

                String[] acceptFormats = accept.split(",");
                float qJsonValue = 0;
                float qTextFormat = 0;
                for (String format : acceptFormats) {
                    if (format.contains(TEXT_PLAIN) || format.contains(MediaType.WILDCARD) || format.contains("text/*")) {
                        String[] splitTextFormat = format.split(";");
                        if (splitTextFormat.length == 2) {
                            qTextFormat = Float.parseFloat(splitTextFormat[1].substring(2));
                        } else {
                            qTextFormat = 1;
                        }
                    } else if (format.contains(APPLICATION_JSON) || format.contains(APPLICATION_WILDCARD)) {
                        String[] splitJsonFormat = format.split(";");
                        if (splitJsonFormat.length == 2) {
                            qJsonValue = Float.parseFloat(splitJsonFormat[1].substring(2));
                        } else {
                            qJsonValue = 1;
                        }
                    } // else { no other formats supported by Payara, ignored }
                }

                //if neither JSON or plain text are supported
                if (qJsonValue == 0 && qTextFormat == 0) {
                    response.sendError(SC_NOT_ACCEPTABLE, String.format("[%s] not acceptable", accept));
                } else if (qJsonValue > qTextFormat) {
                    outputWriter = new JsonMetricWriter(writer);
                } else {
                    outputWriter = new PrometheusWriter(writer);
                }
                break;
            case OPTIONS:
                if (accept.contains(APPLICATION_JSON) || accept.contains(APPLICATION_WILDCARD)) {
                    outputWriter = new JsonMetadataWriter(writer);
                } else {
                    response.sendError(
                            SC_NOT_ACCEPTABLE,
                            String.format("[%s] not acceptable", accept));
                }   break;
            default:
                response.sendError(
                        SC_METHOD_NOT_ALLOWED,
                        String.format("HTTP method [%s] not allowed", method));
                break;
        }
        return outputWriter;
    }

    /**
     * Handles the HTTP <code>GET</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    /**
     * Handles the HTTP <code>OPTIONS</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    private class MetricsRequest {

        private final String registryName;
        private final String metricName;

        public MetricsRequest(HttpServletRequest request) {
                String pathInfo = request.getPathInfo() != null ? request.getPathInfo().substring(1) : EMPTY_STRING;
                String[] pathInfos = pathInfo.split("/");
                registryName = pathInfos.length > 0 ? pathInfos[0] : null;
                metricName = pathInfos.length > 1 ? pathInfos[1] : null;
        }

        public String getRegistryName() {
            return registryName;
        }

        public String getMetricName() {
            return metricName;
        }

        public boolean isRegistryRequested(){
            return registryName != null && !registryName.isEmpty();
        }

        public boolean isMetricRequested() {
            return metricName != null && !metricName.isEmpty();
        }

    }

}
