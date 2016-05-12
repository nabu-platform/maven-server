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
