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

import be.nabu.libs.maven.api.Artifact;

public class CreateResourceRepositoryEvent {
	private Artifact artifact;
	private boolean isInternal;

	public CreateResourceRepositoryEvent(Artifact resourceArtifact, boolean isInternal) {
		this.artifact = resourceArtifact;
		this.isInternal = isInternal;
	}

	public Artifact getArtifact() {
		return artifact;
	}

	public boolean isInternal() {
		return isInternal;
	}
}
