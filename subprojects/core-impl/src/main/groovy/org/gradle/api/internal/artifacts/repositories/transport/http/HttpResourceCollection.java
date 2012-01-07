/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.repositories.transport.http;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpMethodRetryHandler;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.io.output.CloseShieldOutputStream;
import org.apache.ivy.core.module.id.ArtifactRevisionId;
import org.apache.ivy.plugins.repository.AbstractRepository;
import org.apache.ivy.plugins.repository.BasicResource;
import org.apache.ivy.plugins.repository.RepositoryCopyProgressListener;
import org.apache.ivy.plugins.repository.Resource;
import org.apache.ivy.plugins.repository.TransferEvent;
import org.apache.ivy.util.FileUtil;
import org.apache.ivy.util.url.ApacheURLLister;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.artifacts.repositories.PasswordCredentials;
import org.gradle.api.internal.artifacts.ivyservice.filestore.CachedArtifact;
import org.gradle.api.internal.artifacts.ivyservice.filestore.ExternalArtifactCache;
import org.gradle.api.internal.artifacts.repositories.transport.ResourceCollection;
import org.gradle.internal.UncheckedException;
import org.gradle.util.GUtil;
import org.gradle.util.GradleVersion;
import org.jfrog.wharf.ivy.checksum.ChecksumType;
import org.jfrog.wharf.ivy.util.WharfUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A repository which uses commons-httpclient to access resources using HTTP/HTTPS.
 */
public class HttpResourceCollection extends AbstractRepository implements ResourceCollection {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpResourceCollection.class);
    private final Map<String, HttpResource> resources = new HashMap<String, HttpResource>();
    private final HttpClient client = new HttpClient();
    private final HttpProxySettings proxySettings;
    private final RepositoryCopyProgressListener progress = new RepositoryCopyProgressListener(this);

    private final ExternalArtifactCache externalArtifactCache;

    public HttpResourceCollection(HttpSettings httpSettings, ExternalArtifactCache externalArtifactCache) {
        PasswordCredentials credentials = httpSettings.getCredentials();
        if (GUtil.isTrue(credentials.getUsername())) {
            client.getParams().setAuthenticationPreemptive(true);
            client.getState().setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(credentials.getUsername(), credentials.getPassword()));
        }
        this.proxySettings = httpSettings.getProxySettings();
        this.externalArtifactCache = externalArtifactCache;
    }

    public HttpResource getResource(final String source, ArtifactRevisionId artifactId) throws IOException {
        LOGGER.debug("Constructing GET resource: {}", source);

        List<CachedArtifact> cachedArtifacts = new ArrayList<CachedArtifact>();
        externalArtifactCache.addMatchingCachedArtifacts(artifactId, cachedArtifacts);

        HttpResource resource = initGet(source, cachedArtifacts);
        resources.put(source, resource);
        return resource;
    }

    public Resource getResource(String source, ArtifactRevisionId artifactRevisionId, boolean forDownload) throws IOException {
        if (forDownload) {
            return getResource(source, artifactRevisionId);
        }
        LOGGER.debug("Constructing HEAD resource: {}", source);
        return initHead(source);
    }

    public HttpResource getResource(String source) throws IOException {
        return getResource(source, null);
    }

    private HttpResource initGet(String source, List<CachedArtifact> candidates) {
        // First see if we can use any of the candidates directly.
        if (candidates.size() > 0) {
            CachedHttpResource cachedResource = findCachedResource(source, candidates);
            if (cachedResource != null) {
                return cachedResource;
            }
        }

        GetMethod method = new GetMethod(source);
        configureMethod(method);
        int result;
        try {
            result = executeMethod(method);
        } catch (IOException e) {
            throw new UncheckedIOException(String.format("Could not GET '%s'.", source), e);
        }
        if (result == 404) {
            LOGGER.info("Resource missing. [HTTP GET: {}]", source);
            return new MissingHttpResource(source);
        }
        if (!wasSuccessful(result)) {
            LOGGER.info("Failed to get resource: {} ({}). [HTTP GET: {}]", new Object[]{result, method.getStatusText(), source});
            throw new UncheckedIOException(String.format("Could not GET '%s'. Received status code %s from server: %s", source, result, method.getStatusText()));
        }
        LOGGER.info("Resource found. [HTTP GET: {}]", source);
        return new HttpGetResource(source, method);
    }

    private HttpResource initHead(String source) {
        HeadMethod method = new HeadMethod(source);
        configureMethod(method);
        int result;
        try {
            result = executeMethod(method);
        } catch (IOException e) {
            throw new UncheckedIOException(String.format("Could not HEAD '%s'.", source), e);
        }
        if (result == 404) {
            LOGGER.info("Resource missing. [HTTP HEAD: {}]", source);
            return new MissingHttpResource(source);
        }
        if (!wasSuccessful(result)) {
            LOGGER.info("Failed to get resource: {} ({}). [HTTP HEAD: {}]", new Object[]{result, method.getStatusText(), source});
            throw new UncheckedIOException(String.format("Could not HEAD '%s'. Received status code %s from server: %s", source, result, method.getStatusText()));
        }
        LOGGER.info("Resource found. [HTTP HEAD: {}]", source);
        return new HttpGetResource(source, method);
    }

    private CachedHttpResource findCachedResource(String source, List<CachedArtifact> candidates) {
        ChecksumType checksumType = ChecksumType.sha1;
        String checksumUrl = source + checksumType.ext();

        String sha1 = downloadChecksum(checksumUrl);
        if (sha1 == null) {
            LOGGER.info("Checksum {} unavailable. [HTTP GET: {}]", checksumType, checksumUrl);
        } else {
            for (CachedArtifact candidate : candidates) {
                if (candidate.getSha1().equals(sha1)) {
                    LOGGER.info("Checksum {} matched cached resource: [HTTP GET: {}]", checksumType, checksumUrl);
                    return new CachedHttpResource(source, candidate, HttpResourceCollection.this);
                }
            }
            LOGGER.info("Checksum {} did not match cached resources: [HTTP GET: {}]", checksumType, checksumUrl);
        }
        return null;
    }

    private String downloadChecksum(String checksumUrl) {
        GetMethod get = new GetMethod(checksumUrl);
        configureMethod(get);
        try {
            int result = executeMethod(get);
            if (wasSuccessful(result)) {
                return WharfUtils.getCleanChecksum(get.getResponseBodyAsString());
            }
            if (result != 404) {
                LOGGER.info("Request for checksum at {} failed: {}", checksumUrl, get.getStatusText());
            }
            return null;
        } catch (IOException e) {
            LOGGER.warn("Checksum missing at {} due to: {}", checksumUrl, e.getMessage());
            return null;
        } finally {
            get.releaseConnection();
        }
    }

    public void get(String source, File destination) throws IOException {
        throw new UnsupportedOperationException();
    }

    public void downloadResource(Resource res, File destination) throws IOException {
        if (!(res instanceof HttpResource)) {
            throw new IllegalArgumentException("Can only download HttpResource");
        }
        HttpResource resource = (HttpResource) res;
        fireTransferInitiated(resource, TransferEvent.REQUEST_GET);
        try {
            progress.setTotalLength(resource.getContentLength());
            resource.writeTo(destination, progress);
        } catch (IOException e) {
            fireTransferError(e);
            throw e;
        } catch (Exception e) {
            fireTransferError(e);
            throw UncheckedException.asUncheckedException(e);
        } finally {
            progress.setTotalLength(null);
        }
    }

    @Override
    protected void put(final File source, String destination, boolean overwrite) throws IOException {
        LOGGER.debug("Attempting to put resource {}.", destination);
        assert source.isFile();
        fireTransferInitiated(new BasicResource(destination, true, source.length(), source.lastModified(), false), TransferEvent.REQUEST_PUT);
        try {
            progress.setTotalLength(source.length());
            doPut(source, destination);
        } catch (IOException e) {
            fireTransferError(e);
            throw e;
        } catch (Exception e) {
            fireTransferError(e);
            throw UncheckedException.asUncheckedException(e);
        } finally {
            progress.setTotalLength(null);
        }
    }

    private void doPut(File source, String destination) throws IOException {
        PutMethod method = new PutMethod(destination);
        configureMethod(method);
        method.setRequestEntity(new FileRequestEntity(source));
        int result = executeMethod(method);
        if (!wasSuccessful(result)) {
            throw new IOException(String.format("Could not PUT '%s'. Received status code %s from server: %s", destination, result, method.getStatusText()));
        }
    }

    private void configureMethod(HttpMethod method) {
        method.setRequestHeader("User-Agent", "Gradle/" + GradleVersion.current().getVersion());
        method.setRequestHeader("Accept-Encoding", "identity");
        method.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new HttpMethodRetryHandler() {
            public boolean retryMethod(HttpMethod method, IOException exception, int executionCount) {
                return false;
            }
        });
    }

    private int executeMethod(HttpMethod method) throws IOException {
        LOGGER.debug("Performing HTTP GET: {}", method.getURI());
        configureProxyIfRequired(method);
        return client.executeMethod(method);
    }

    private void configureProxyIfRequired(HttpMethod method) throws URIException {
        HttpProxySettings.HttpProxy proxy = proxySettings.getProxy(method.getURI().getHost());
        if (proxy != null) {
            setProxyForClient(client, proxy);
        } else {
            client.getHostConfiguration().setProxyHost(null);
        }
    }

    private void setProxyForClient(HttpClient httpClient, HttpProxySettings.HttpProxy proxy) {
        // Only set proxy host once
        if (client.getHostConfiguration().getProxyHost() != null) {
            return;
        }
        httpClient.getHostConfiguration().setProxy(proxy.host, proxy.port);
        if (proxy.username != null) {
            httpClient.getState().setProxyCredentials(AuthScope.ANY, new UsernamePasswordCredentials(proxy.username, proxy.password));
        }
    }

    public List list(String parent) throws IOException {
        // Parse standard directory listing pages served up by Apache
        ApacheURLLister urlLister = new ApacheURLLister();
        List<URL> urls = urlLister.listAll(new URL(parent));
        if (urls != null) {
            List<String> ret = new ArrayList<String>(urls.size());
            for (URL url : urls) {
                ret.add(url.toExternalForm());
            }
            return ret;
        }
        return null;
    }

    private boolean wasSuccessful(int result) {
        return result >= 200 && result < 300;
    }

    private class FileRequestEntity implements RequestEntity {
        private final File source;

        public FileRequestEntity(File source) {
            this.source = source;
        }

        public boolean isRepeatable() {
            return false;
        }

        public void writeRequest(OutputStream out) throws IOException {
            FileInputStream inputStream = new FileInputStream(source);
            try {
                FileUtil.copy(inputStream, new CloseShieldOutputStream(out), progress);
            } finally {
                inputStream.close();
            }
        }

        public long getContentLength() {
            return source.length();
        }

        public String getContentType() {
            return "application/octet-stream";
        }
    }
}
