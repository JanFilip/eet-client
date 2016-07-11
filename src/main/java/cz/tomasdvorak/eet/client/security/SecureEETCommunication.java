package cz.tomasdvorak.eet.client.security;

import cz.etrzby.xml.EET;
import cz.etrzby.xml.EETService;
import cz.tomasdvorak.eet.client.dto.CommunicationMode;
import cz.tomasdvorak.eet.client.dto.EndpointType;
import cz.tomasdvorak.eet.client.logging.WebserviceLogging;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.message.Message;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.cxf.ws.security.wss4j.WSS4JInInterceptor;
import org.apache.cxf.ws.security.wss4j.WSS4JOutInterceptor;
import org.apache.logging.log4j.Logger;
import org.apache.wss4j.dom.handler.WSHandlerConstants;

import javax.xml.ws.BindingProvider;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class SecureEETCommunication {

    private static final String CRYPTO_INSTANCE_KEY = "eetCryptoInstance";
    private static final String JAVAX_NET_SSL_KEY_STORE_PASSWORD = "javax.net.ssl.keyStorePassword";
    private static final long RECEIVE_TIMEOUT = 10_000L; // 10s timeout for webservice call - TODO: should it be adjustable?


    private static final Logger logger = org.apache.logging.log4j.LogManager.getLogger(ClientKey.class);

    /**
     * Service instance is thread safe and cachable, so create just one instance during initialization of the class
     */
    private static final EETService WEBSERVICE = new EETService(SecureEETCommunication.class.getResource("schema/EETServiceSOAP.wsdl"));

    /**
     * Signing of data and requests
     */
    private final ClientKey clientKey;

    /**
     * Validation of response signature
     */
    private final ServerKey serverRootCa;

    public SecureEETCommunication(final ClientKey clientKey, final ServerKey serverKey) {
        this.clientKey = clientKey;
        this.serverRootCa = serverKey;
    }

    protected EET getPort(final CommunicationMode mode, final EndpointType endpointType) {
        EET port = WEBSERVICE.getEETServiceSOAP();
        final Client clientProxy = ClientProxy.getClient(port);
        ensureHTTPSKeystorePassword();
        configureEndpointUrl(port, endpointType.getWebserviceUrl());
        configureSchemaValidation(port);
        configureTimeout(clientProxy);
        configureLogging(clientProxy);
        configureSigning(clientProxy, mode);
        return port;
    }

    protected ClientKey getClientKey() {
        return clientKey;
    }

    private void ensureHTTPSKeystorePassword() {
        if(System.getProperty(JAVAX_NET_SSL_KEY_STORE_PASSWORD) == null) {
            // there is not set keystore password (needed for HTTPS communication handshake), set the usual default one
            // TODO: is this assumption ok?
            System.setProperty(JAVAX_NET_SSL_KEY_STORE_PASSWORD, "changeit");
        }
    }

    /**
     * Sign our request with the client key par.
     */
    private void configureSigning(final Client clientProxy, final CommunicationMode mode) {
        WSS4JOutInterceptor wssOut = createSigningInterceptor();
        clientProxy.getOutInterceptors().add(wssOut);
        WSS4JInInterceptor wssIn = createValidatingInterceptor(mode);
        clientProxy.getInInterceptors().add(wssIn);
    }

    /**
     * Checks, if the response is signed by a key produced by CA, which do we accept (provided to this client)
     */
    private WSS4JInInterceptor createValidatingInterceptor(final CommunicationMode mode) {
        Map<String,Object> inProps = new HashMap<>();
        inProps.put(WSHandlerConstants.ACTION, WSHandlerConstants.SIGNATURE); // only sign, do not encrypt
        inProps.put(CRYPTO_INSTANCE_KEY, serverRootCa.getCrypto());
        inProps.put(WSHandlerConstants.SIG_PROP_REF_ID, CRYPTO_INSTANCE_KEY);
        inProps.put(WSHandlerConstants.SIG_SUBJECT_CERT_CONSTRAINTS, ".*O=Česká republika - Generální finanční ředitelství.*CN=Elektronická evidence tržeb.*");
        // TODO:fix CRL
//        inProps.put(WSHandlerConstants.ENABLE_REVOCATION, "true");

        return new WSS4JInInterceptor(inProps) {

            /**
             * This is a giant and unsecure HACK! The response is digitally signed only, when the communication mode is set to REAL (overeni=false)
             * and the response is valid (which cannot be checked in advance).
             *
             * So our only change is to check the signature only, when the communication is set to real and there is no
             * error in response.
             */
            @Override
            public void handleMessage(final SoapMessage msg) throws Fault {
                if (mode.isCheckOnly()) {
                    logger.warn("Running in " + mode + " mode, no response signature verification available!");
                } else if (isErrorResponse(msg)) {
                    logger.warn("Validation error, no response signature verification available!");
                } else {
                    super.handleMessage(msg);
                }
            }
        };
    }

    /**
     * Check, whether the response contains error headers.
     * TODO: is there a better solution?
     */
    private boolean isErrorResponse(final SoapMessage msg) {
        final Map<String, List<String>> headers = (Map<String, List<String>>) msg.get(Message.PROTOCOL_HEADERS);
        final List<String> strings = headers.get("X-Backside-Transport");
        final Set<String> status = strings.stream().flatMap(s -> Arrays.stream(s.split(" "))).collect(Collectors.toSet());
        return status.contains("FAIL");
    }

    private WSS4JOutInterceptor createSigningInterceptor() {
        Map<String,Object> signingProperties = new HashMap<>();
        signingProperties.put(WSHandlerConstants.ACTION, WSHandlerConstants.SIGNATURE); // only sign, do not encrypt
        signingProperties.put(WSHandlerConstants.SIGNATURE_USER, this.clientKey.getAlias());
        signingProperties.put(WSHandlerConstants.PW_CALLBACK_REF, this.clientKey.getClientPasswordCallback());
        signingProperties.put(CRYPTO_INSTANCE_KEY, clientKey.getCrypto());
        signingProperties.put(WSHandlerConstants.SIG_PROP_REF_ID, CRYPTO_INSTANCE_KEY);
        signingProperties.put(WSHandlerConstants.SIG_KEY_ID, "DirectReference");
        signingProperties.put(WSHandlerConstants.SIG_ALGO, "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256");
        signingProperties.put(WSHandlerConstants.SIG_DIGEST_ALGO, "http://www.w3.org/2001/04/xmlenc#sha256");
        return new WSS4JOutInterceptor(signingProperties);
    }

    private void configureTimeout(final Client clientProxy) {
        final HTTPConduit conduit = (HTTPConduit)clientProxy.getConduit();
        final HTTPClientPolicy policy = new HTTPClientPolicy();
        policy.setReceiveTimeout(RECEIVE_TIMEOUT);
        conduit.setClient(policy);
    }

    private void configureEndpointUrl(final EET remote, final String webserviceUrl) {
        final Map<String, Object> requestContext = ((BindingProvider)remote).getRequestContext();
        requestContext.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, webserviceUrl);
    }

    private void configureSchemaValidation(final EET remote) {
        final Map<String, Object> requestContext = ((BindingProvider)remote).getRequestContext();
        requestContext.put("schema-validation-enabled", "true");
    }

    private void configureLogging(final Client clientProxy) {
        clientProxy.getInInterceptors().add(WebserviceLogging.LOGGING_IN_INTERCEPTOR);
        clientProxy.getOutInterceptors().add(WebserviceLogging.LOGGING_OUT_INTERCEPTOR);
    }
}