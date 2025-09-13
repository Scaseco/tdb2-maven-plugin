package org.aksw.maven.plugin.jena;

import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.graph.Dependency;

public class AetherUtils {
    public static org.apache.maven.artifact.Artifact convert(Artifact a) {
        return convert(a, "compile");
    }

    public static org.apache.maven.artifact.Artifact convert(Artifact a, String scope) {
        org.apache.maven.artifact.Artifact result = new org.apache.maven.artifact.DefaultArtifact(
            a.getGroupId(), a.getArtifactId(), a.getVersion(), scope,
            a.getExtension(), a.getClassifier(), new DefaultArtifactHandler(a.getExtension()));
        return result;
    }

    public static Artifact convert(org.apache.maven.artifact.Artifact a) {
        Artifact result = new DefaultArtifact(
            a.getGroupId(), a.getArtifactId(), a.getVersion(),
            a.getType(), a.getClassifier(), new DefaultArtifactType(a.getType()));
        return result;
    }

    public static Dependency convert(org.apache.maven.model.Dependency md) {
        Artifact a = toArtifact(md);
        Dependency result = new Dependency(a, md.getScope());
        return result;
    }

//    public static org.apache.maven.model.Dependency convert(Dependency md) {
//        org.apache.maven.model.Dependency result = new org.apache.maven.model.Dependency();
//
//        // return convert(md.getArtifact(), md.getScope());
//        return result;
//    }

    public static Artifact toArtifact(org.apache.maven.model.Dependency md) {
        Artifact result = new DefaultArtifact(
            md.getGroupId(), md.getArtifactId(), md.getClassifier(),
            md.getType(), md.getVersion(), new DefaultArtifactType(md.getType()));
        return result;
    }
}
