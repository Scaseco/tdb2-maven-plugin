package org.aksw.maven.plugin.jena;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Dependency;

public class PomUtils {

    public static Dependency toDependency(Artifact artifact) {
        Dependency result = new Dependency();
        result.setGroupId(artifact.getGroupId());
        result.setArtifactId(artifact.getArtifactId());
        result.setVersion(artifact.getVersion());
        result.setClassifier(artifact.getClassifier()); // may be null
        result.setScope(artifact.getScope());
        result.setType(artifact.getType());
        // result.setOptional(artifact.isOptional());
        return result;
    }

    public static Artifact toArtifact(Dependency dep) {
        Artifact result = new DefaultArtifact(
            dep.getGroupId(), dep.getArtifactId(), dep.getVersion(),
            dep.getScope(), dep.getType(), dep.getClassifier(), new DefaultArtifactHandler(dep.getType()));
        return result;
    }

    public static String toString(Artifact coord) {
        String t = coord.getType();
        String c = coord.getClassifier();

        String suffix =
                (t == null || t.isEmpty() ? "" : ":" + t) +
                (c == null || c.isEmpty() ? "" : ":" + c);

        String result = coord.getGroupId() + ":" + coord.getArtifactId() + ":" + coord.getVersion() + suffix;
        return result;
    }
}
