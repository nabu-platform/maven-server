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
