package be.nabu.libs.maven;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

import be.nabu.libs.maven.BaseArtifact;
import be.nabu.libs.resources.ResourceUtils;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.TimestampedResource;
import be.nabu.utils.io.IOUtils;

public class ResourceArtifact extends BaseArtifact {

	private ReadableResource resource;
	private Date lastModified = new Date();
	
	public ResourceArtifact(ReadableResource resource) throws IOException {
		this.resource = resource;
		parseProperties();
	}
	
	@Override
	public Date getLastModified() {
		if (resource instanceof TimestampedResource) {
			return ((TimestampedResource) resource).getLastModified();
		}
		else {
			return lastModified;
		}
	}

	@Override
	public InputStream getContent() throws IOException {
		return IOUtils.toInputStream(resource.getReadable());
	}

	@Override
	protected String getArtifactName() {
		return resource.getName();
	}
	
	@Override
	public String toString() {
		return ResourceUtils.getURI(resource).toString();
	}

}
