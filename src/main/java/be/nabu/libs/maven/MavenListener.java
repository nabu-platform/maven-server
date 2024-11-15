/*
* Copyright (C) 2016 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.libs.maven;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.events.impl.EventDispatcherImpl;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPServer;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.server.HTTPServerUtils;
import be.nabu.libs.maven.RepositoryUtils.HashAlgorithm;
import be.nabu.libs.maven.api.Artifact;
import be.nabu.libs.maven.api.Repository;
import be.nabu.libs.maven.api.WritableRepository;
import be.nabu.libs.resources.ResourceUtils;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.mime.api.ContentPart;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.PlainMimeContentPart;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class MavenListener implements EventHandler<HTTPRequest, HTTPResponse> {

	private Repository repository;
	private String root;
	
	public static void main(String...args) throws IOException, URISyntaxException {
		HTTPServer server = HTTPServerUtils.newServer(1111, 20, new EventDispatcherImpl());
		server.getDispatcher(null).subscribe(
			HTTPRequest.class, 
			new MavenListener(new ResourceRepository(ResourceUtils.mkdir(new URI("file:/home/alex/maven"), null)), "/")
		).filter(HTTPServerUtils.filterPath("/"));
		server.start();
	}
	
	public MavenListener(Repository repository, String path) {
		this.repository = repository;
		this.root = path.endsWith("/") ? path : path + "/";
	}
	
	public MavenListener(Repository repository) {
		this(repository, "/");
	}
	
	private String style;	
	
	@Override
	public HTTPResponse handle(HTTPRequest request) {
		try {
			// always do a scan
			repository.scan();
			if (request.getMethod().equalsIgnoreCase("PUT")) {
				return put(request);
			}
			else if (request.getMethod().equalsIgnoreCase("GET")) {
				return get(request);
			}
			else {
				throw new HTTPException(405, "Method not allowed");
			}
		}
		catch (IOException e) {
			throw new HTTPException(500, e);
		}
	}
	
	private HTTPResponse put(HTTPRequest request) throws HTTPException, IOException {
		if (getRepository() instanceof WritableRepository) {
			// maven will try to write the actual artifact and its hashes
			// then it will try to write the pom and its hashes
			// then it will try to write the maven-metadata.xml and its hashes
			// finally it will try to write the maven-metadata.xml and its hashes for the group
			// we are currently only interested in the artifact, the rest is generated by the repository
			// note that it is also possible that maven sends along a "tests" artifact which contains the test files for a specific artifact (for reusable test parts)
			String name = request.getTarget().replaceAll(".*?([^/]+)$", "$1");
			if (!name.equals("maven-metadata.xml") && !name.endsWith("sha1") && !name.endsWith("md5")) {
				if (!(request.getContent() instanceof ContentPart)) {
					throw new HTTPException(400, "Expecting a content part");
				}
				byte [] bytes = IOUtils.toBytes(((ContentPart) request.getContent()).getReadable());
				// if the file name ends in "pom", it is either the pom from another artifact and it can be safely ignored or it is a standalone artifact of type pom in which case we need to process it
				// check the contents to see for packaging information
				Properties properties;
				if (name.endsWith("pom")) {
					// get packaging type
					if (new String(bytes).replaceAll("(?s).*<packaging>([^<]+).*", "$1").equals("pom")) {
						properties = RepositoryUtils.getPropertiesFromXML(new ByteArrayInputStream(bytes));
					}
					else {
						// this is maven trying to upload the pom file of the artifact, ignore it
						return new DefaultHTTPResponse(200, "OK", new PlainMimeEmptyPart(null, 
							new MimeHeader("Content-Length", "0")
						));
					}
				}
				else {
					properties = RepositoryUtils.getPropertiesFromZip(new ByteArrayInputStream(bytes));
					if (properties == null) {
						throw new HTTPException(400, "Could not find the pom.properties file in the maven artifact");
					}
				}
				((WritableRepository) getRepository()).create(properties.getProperty("groupId"), properties.getProperty("artifactId"), properties.getProperty("version"), name.replaceAll(".*?([^.]+)$", "$1").toLowerCase(), new ByteArrayInputStream(bytes), name.matches(".*-tests\\.[^.]+$"));
			}
			// it was either uploaded successfully or ignored, either way signal the ok
			return new DefaultHTTPResponse(200, "OK", new PlainMimeEmptyPart(null, 
				new MimeHeader("Content-Length", "0")
			));
		}
		else {
			throw new HTTPException(403, "The repository does not support creation of new artifacts");
		}
	}
	
	private HTTPResponse get(HTTPRequest request) throws HTTPException, IOException {
		String path = request.getTarget() == null ? null : request.getTarget().substring(root.length());
		if (path == null || path.isEmpty() || path.equals("/")) {
			return createResponse(listGroups(), "text/html");
		}
		else {
			if (path.startsWith("/")) {
				path = path.substring(1);
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
			String [] parts = path.split("/");
			// you have a specific group, list all the artifacts
			if (parts.length == 1) {
				if (parts[0].equals("style.css")) {
					return createResponse(getStyle(), "text/css");
				}
				else {
					return createResponse(listArtifacts(path, parts[0]), "text/html");
				}
			}
			// a specific artifact, list the versions
			else if (parts.length == 2) {
				return createResponse(listVersions(path, parts[0], parts[1]), "text/html");
			}
			// a specific version or the metadata.xml of the group
			else if (parts.length == 3) {
				// metadata of the group
				if (parts[2].equals("maven-metadata.xml")) {
					return createResponse(getRepository().getMetaData(parts[0], parts[1]), "application/xml");
				}
				else if (parts[2].equals("maven-metadata.xml.sha1")) {
					return createResponse(getSha1(getRepository().getMetaData(parts[0], parts[1]), path, "maven-metadata.xml"), "text/plain");
				}
				else if (parts[2].equals("maven-metadata.xml.md5")) {
					return createResponse(getMd5(getRepository().getMetaData(parts[0], parts[1]), path, "maven-metadata.xml"), "text/plain");
				}
				else {
					return createResponse(showArtifact(path, parts[0], parts[1], parts[2]), "text/html");
				}
			}
			// a specific file, stream it
			else if (parts.length == 4) {	
				return downloadArtifact(path, parts[0], parts[1], parts[2], parts[3]);
			}
			// if there are 5 parts and the last one is a maven-metadata.xml one, we are dealing with a group maven-metadata.xml request
			else if (parts.length == 5 && parts[4].startsWith("maven-metadata.xml")) {
				String group = null;
				for (int i = 0; i < parts.length - 2; i++) {
					group = group == null ? parts[i] : group + "." + parts[i];
				}
				// rephrase request
				return handle(new DefaultHTTPRequest(request.getMethod(), root + group + "/" + parts[parts.length - 2] + "/" + parts[parts.length - 1], request.getContent()));
			}
			// more parts means maven is requesting with a split group (. replaced with /)
			// reroute the request
			else {
				String group = null;
				for (int i = 0; i < parts.length - 3; i++) {
					group = group == null ? parts[i] : group + "." + parts[i];
				}
				return handle(new DefaultHTTPRequest(request.getMethod(), root + group + "/" + parts[parts.length - 3] + "/" + parts[parts.length - 2] + "/" + parts[parts.length - 1], request.getContent()));
			}
		}
	}
	
	private String getStyle() throws HTTPException {
		if (style == null) {
			InputStream input = getClass().getResourceAsStream("/maven-style.css");
			if (input == null) {
				throw new HTTPException(404, "Could not find stylesheet");
			}
			try {
				try {
					style = new String(IOUtils.toBytes(IOUtils.wrap(input)), Charset.forName("UTF-8"));
				}
				finally {
					input.close();
				}
			}
			catch (IOException e) {
				throw new HTTPException(500, e);
			}
		}
		return style;
	}

	private HTTPResponse downloadArtifact(String path, String groupId, String artifactId, String version, String fragment) throws HTTPException, IOException {
		// the filename can be "-tests.jar" but also "-tests.jar.md5" etc
		Artifact artifact = getRepository().getArtifact(groupId, artifactId, version, fragment.matches(".*-tests\\.[^.]+(\\.[^.]+|)*$"));

		if (artifact == null) {
			throw new HTTPException(404, "Can not find the artifact " + groupId + "-" + artifactId + "-" + version);
		}

		String artifactName = artifact.getArtifactId() + "-" + artifact.getVersion() + "." + artifact.getPackaging();
		String pomName = artifact.getArtifactId() + "-" + artifact.getVersion() + ".pom";

		Header lastModified = new MimeHeader("Last-Modified", "" + artifact.getLastModified().getTime());
		if (fragment.equals("maven-metadata.xml")) {
			return createResponse(getRepository().getMetaData(artifact), "application/xml", lastModified);
		}
		else if (fragment.equals("maven-metadata.xml.md5")) {
			return createResponse(getMd5(getRepository().getMetaData(artifact), path, "maven-metadata.xml"), "text/plain", lastModified);
		}
		else if (fragment.equals("maven-metadata.xml.sha1")) {
			return createResponse(getSha1(getRepository().getMetaData(artifact), path, "maven-metadata.xml"), "text/plain", lastModified);
		}
		else if (fragment.endsWith("pom")) {
			return createResponse(artifact.getPom(), "application/xml", lastModified);
		}
		else if (fragment.endsWith("pom.md5")) {
			return createResponse(getMd5(artifact.getPom(), path, pomName), "text/plain", lastModified);
		}
		else if (fragment.endsWith("pom.sha1")) {
			return createResponse(getSha1(artifact.getPom(), path, pomName), "text/plain", lastModified);
		}
		else if (fragment.endsWith("ar")) {
			return createResponse(artifact.getContent(), "application/octet-stream", lastModified);
		}
		else if (fragment.endsWith("ar.md5")) {
			return createResponse(getMd5(artifact.getPom(), path, artifactName), "text/plain", lastModified);
		}
		else if (fragment.endsWith("ar.sha1")) {
			return createResponse(getSha1(artifact.getPom(), path, artifactName), "text/plain", lastModified);
		}
		else {
			throw new HTTPException(404, "Not Found");
		}
	}

	private String showArtifact(String path, String groupId, String artifactId, String version) throws IOException, HTTPException {
		Artifact artifact = getRepository().getArtifact(groupId, artifactId, version, false);

		if (artifact == null) {
			throw new HTTPException(404, "Can not find the artifact " + groupId + "-" + artifactId + "-" + version);
		}

		String artifactName = artifact.getArtifactId() + "-" + artifact.getVersion() + "." + artifact.getPackaging();
		String pomName = artifact.getArtifactId() + "-" + artifact.getVersion() + ".pom";
		String html = "<a href='" + root + "'>Repository</a> &gt; <a href='" + root + groupId + "'>" + groupId + "</a> &gt; <a href='" + root + groupId + "/" + artifactId + "'>" + artifactId + "</a> &gt; <a href='" + root + groupId + "/" + artifactId + "/" + version + "'>" + version + "</a>";
		html += "<h1 title=\"" + groupId + "." + artifactId + "-" + version + "\">Resources</h1><ul>";
		html += "<li><a href='" + root + path.replaceAll("(/|)$", "") + "/..'>..</a></li>";
		html += "<li><a href='" + root + path + "/" + artifactName + "'>" + artifactName + "</a></li>";
		html += "<li><a href='" + root + path + "/" + pomName + "'>" + pomName + "</a></li>";
		html += "<li><a href='" + root + path + "/maven-metadata.xml'>maven-metadata.xml</a></li>";
		html += "</ul><h1>Hashes</h1><ul>";
		html += "<li><a href='" + root + path + "/" + artifactName + ".md5'>" + artifactName + ".md5</a></li>";
		html += "<li><a href='" + root + path + "/" + artifactName + ".sha1'>" + artifactName + ".sha1</a></li>";
		html += "<li><a href='" + root + path + "/" + pomName + ".md5'>" + pomName + ".md5</a></li>";
		html += "<li><a href='" + root + path + "/" + pomName + ".sha1'>" + pomName + ".sha1</a></li>";
		html += "<li><a href='" + root + path + "/maven-metadata.xml.sha1'>maven-metadata.xml.sha1</a></li>";
		html += "<li><a href='" + root + path + "/maven-metadata.xml.md5'>maven-metadata.xml.md5</a></li>";
		html += "</ul>";
		return html;
	}

	public Repository getRepository() {
		return repository;
	}

	private String listGroups() throws IOException {
		String html = "<a href='" + root + "'>Repository</a>";
		html += "<h1>Groups</h1><ul>";
		for (String group : getRepository().getGroups()) {
			html += "<li><a href='" + root + group + "'>" + group + "</a></li>";
		}
		html += "</ul>";
		return html;
	}
	
	private String listArtifacts(String path, String groupId) throws IOException {
		// breadcrumbs
		String html = "<a href='" + root + "'>Repository</a> &gt; <a href='" + root + groupId + "'>" + groupId + "</a>";
		html += "<h1 title=\"" + groupId + "\">Artifacts</h1><ul>";
		html += "<li><a href='" + root + path.replaceAll("/$", "") + "/..'>..</a></li>";
		for (String artifact : getRepository().getArtifacts(groupId)) {
			html += "<li><a href='" + root + path + "/" + artifact + "'>" + artifact + "</a></li>";
		}
		html += "</ul>";
		return html;
	}
	
	private String listVersions(String path, String groupId, String artifactId) throws IOException {
		String html = "<a href='" + root + "'>Repository</a> &gt; <a href='" + root + groupId + "'>" + groupId + "</a> &gt; <a href='" + root + groupId + "/" + artifactId + "'>" + artifactId + "</a>";
		html += "<h1 title=\"" + groupId + "." + artifactId + "\">Versions</h1><ul>";
		html += "<li><a href='" + root + path.replaceAll("/$", "") + "/..'>..</a></li>";
		html += "<li><a href='" + root + path + "/maven-metadata.xml'>maven-metadata.xml</a></li>";
		for (String version : getRepository().getVersions(groupId, artifactId)) {
			html += "<li><a href='" + root + path + "/" + version + "'>" + version + "</a></li>";
		}
		html += "</ul>";
		return html;
	}

	public HTTPResponse createResponse(String content, String mimeType, Header...headers) throws HTTPException {
		if (content == null) {
			throw new HTTPException(404, "Not Found");
		}
		if (mimeType.equals("text/html")) {
			content = "<html><head><link rel=\"stylesheet\" type=\"text/css\" href=\"" + root + "style.css\"/></head><body>" + content + "</body></html>";
		}
		return createResponse(new ByteArrayInputStream(content.getBytes(Charset.forName("UTF-8"))), mimeType, headers);
	}
	
	public HTTPResponse createResponse(InputStream content, String mimeType, Header...headers) throws HTTPException {
		if (content == null) {
			throw new HTTPException(404, "Not Found");
		}
		ByteBuffer buffer = IOUtils.newByteBuffer();
		try {
			IOUtils.copyBytes(IOUtils.wrap(content), buffer);
			// close the buffer to indicate that there is nothing more coming
			buffer.close();
			List<Header> allHeaders = new ArrayList<Header>(Arrays.asList(headers));
			allHeaders.add(new MimeHeader("Content-Type", mimeType));
			allHeaders.add(new MimeHeader("Content-Length", "" + buffer.remainingData()));
			return new DefaultHTTPResponse(200, "OK", new PlainMimeContentPart(null, buffer,
				allHeaders.toArray(new Header[0])
			));
		}
		catch (IOException e) {
			throw new HTTPException(500, e);
		}
		finally {
			try {
				content.close();
			}
			catch (IOException e) {
				// suppress
			}
		}
	}
	
	private String getSha1(InputStream input, String path, String name) throws IOException, HTTPException {
		if (input == null) {
			throw new HTTPException(404, "Not Found");
		}
		return RepositoryUtils.hash(input, HashAlgorithm.SHA1) + " " + path + "/" + name;
	}
	
	private String getMd5(InputStream input, String path, String name) throws IOException, HTTPException {
		if (input == null) {
			throw new HTTPException(404, "Not Found");
		}
		return RepositoryUtils.hash(input, HashAlgorithm.MD5) + " " + path + "/" + name;
	}

}
