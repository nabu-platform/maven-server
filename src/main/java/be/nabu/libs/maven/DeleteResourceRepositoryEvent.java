package be.nabu.libs.maven;

import be.nabu.libs.maven.api.Artifact;

public class DeleteResourceRepositoryEvent {
	
	private Artifact artifact;
	private boolean isInternal;

	public DeleteResourceRepositoryEvent(Artifact resourceArtifact, boolean isInternal) {
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
