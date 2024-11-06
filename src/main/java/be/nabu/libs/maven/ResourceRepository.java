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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import be.nabu.libs.events.EventDispatcherFactory;
import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.maven.api.Artifact;
import be.nabu.libs.maven.api.WritableRepository;
import be.nabu.libs.resources.ResourceUtils;
import be.nabu.libs.resources.api.ManageableContainer;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.Resource;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.libs.resources.api.WritableResource;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.WritableContainer;

public class ResourceRepository extends BaseRepository implements WritableRepository {

	private ResourceContainer<?> root;
	
	private EventDispatcher dispatcher;
	
	private Map<Resource, ResourceArtifact> artifacts = new HashMap<Resource, ResourceArtifact>();
		
	public ResourceRepository(ResourceContainer<?> root) {
		this(root, null);
	}
	public ResourceRepository(ResourceContainer<?> root, EventDispatcher dispatcher) {
		this.root = root;
		this.dispatcher = dispatcher;
	}
	
	@Override
	public synchronized void scan() throws IOException {
		scan(root, true);
	}
	
	public synchronized void scan(boolean recursive) throws IOException {
		scan(root, recursive);
	}
	
	private synchronized void scan(ResourceContainer<?> container, boolean recursive) throws IOException {
		for (Resource child : container) {
			if (child.getName().endsWith(".jar") || child.getName().endsWith(".war") || child.getName().endsWith(".pom")) {
				if (!artifacts.containsKey(child)) {
					ResourceArtifact artifact = new ResourceArtifact((ReadableResource) child);
					// if the extension is pom but the packaging is not, it is not actually an interesting pom
					if (child.getName().endsWith(".pom")) {
						// if the packaging declared in the pom file is not "pom", it is simply a descriptive pom, ignore it
						if (!artifact.getPackaging().equals("pom")) {
							continue;
						}
						// otherwise, let's check for an actual artifact by the same name
						if (container.getChild(child.getName().replaceAll("\\.pom$", ".jar")) != null
								|| container.getChild(child.getName().replaceAll("\\.pom$", ".war")) != null) {
							continue;
						}
					}
					artifacts.put(child, artifact);
				}
			}
			else if (recursive && child instanceof ResourceContainer) {
				scan((ResourceContainer<?>) child, recursive);
			}
		}
	}

	@Override
	protected List<? extends Artifact> getArtifacts() {
		return new ArrayList<ResourceArtifact>(artifacts.values());
	}

	@Override
	public Artifact create(String groupId, String artifactId, String version, String packaging, InputStream input, boolean isTest) throws IOException {
		Artifact current = getArtifact(groupId, artifactId, version, isTest);
		if (current != null) {
			getDispatcher().fire(new DeleteResourceRepositoryEvent(current, isInternal(groupId)), this);	
		}
		String fileName = formatFileName(groupId, artifactId, version, packaging);
		
		// if it's a test, append that to the filename
		if (isTest) {
			fileName = fileName.replaceAll("(.*)(\\.[^.]+)$", "$1-tests$2");
		}

		if (fileName.startsWith("/")) {
			fileName = fileName.substring(1);
		}
		
		String path = fileName.indexOf('/') >= 0 ? fileName.replaceAll("/[^/]+$", "") : null;
		fileName = fileName.indexOf('/') >= 0 ? fileName.replaceAll(".*/([^/]+)$", "$1") : fileName;
		
		ResourceContainer<?> target = path == null ? root : ResourceUtils.mkdirs(root, path);
		Resource resource = target.getChild(fileName);
		if (resource == null) {
			resource = ((ManageableContainer<?>) target).create(fileName, "application/zip");
		}

		WritableContainer<ByteBuffer> output = ((WritableResource) resource).getWritable();
		try {
			IOUtils.copyBytes(IOUtils.wrap(input), output);
		}
		finally {
			output.close();
		}
		ResourceArtifact artifact = new ResourceArtifact((ReadableResource) resource);
		
		// add it to the artifacts
		artifacts.put(resource, artifact);
		
		getDispatcher().fire(new CreateResourceRepositoryEvent(artifact, isInternal(groupId)), this);
		return artifact;
	}

	public EventDispatcher getDispatcher() {
		if (dispatcher == null) {
			dispatcher = EventDispatcherFactory.getInstance().getEventDispatcher();
		}
		return dispatcher;
	}
	
	public ResourceContainer<?> getRoot() {
		return root;
	}
}
