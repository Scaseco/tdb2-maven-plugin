package org.aksw.maven.plugin.jena;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.aksw.commons.io.util.FileUtils;
import org.aksw.commons.io.util.FileUtils.OverwritePolicy;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.ArchiveStreamProvider;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.compressors.CompressorStreamProvider;
import org.apache.jena.atlas.lib.tuple.Tuple;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.geosparql.configuration.GeoSPARQLOperations;
import org.apache.jena.geosparql.spatial.SpatialIndex;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.exec.UpdateExec;
import org.apache.jena.sparql.modify.request.UpdateLoad;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.tdb2.solver.SolverLibTDB;
import org.apache.jena.tdb2.solver.stats.StatsCollectorNodeId;
import org.apache.jena.tdb2.solver.stats.StatsResults;
import org.apache.jena.tdb2.store.DatasetGraphTDB;
import org.apache.jena.tdb2.store.NodeId;
import org.apache.jena.tdb2.store.nodetable.NodeTable;
import org.apache.jena.tdb2.store.nodetupletable.NodeTupleTable;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import com.google.common.io.CountingInputStream;

@Mojo(name = "load", defaultPhase = LifecyclePhase.PACKAGE)
public class Tdb2MojoShared extends AbstractMojo {

    /** The repository system (Aether) which does most of the management. */
    @Component
    private RepositorySystem repoSystem;

    /** The current repository/network configuration of Maven. */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    /** The project's remote repositories to use for the resolution of project dependencies. */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> projectRepos;

    /** The Maven project */
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Component
    private MavenProjectHelper mavenProjectHelper;

    @Parameter(property = "tdb2.skip", defaultValue = "false")
    protected boolean skip;

    /**
     * Comma separated list of dependency type suffixes which to include.
     * A type matches if the string after stripping the suffix is empty or ends with a dot.
     *
     * Examples:
     * "nt" matches the suffix "nt" because "" is the empty string.
     * "rml.ttl" matches the suffix "ttl" because "rml." ends with a dot.
     * "hint" does NOT match "nt" because "hi" is neither the empty string nor does it end with a dot.
     *
     */
    // XXX Generate content-type+encoding combinations from registries.
    @Parameter(defaultValue = "nt,ttl,nq,trig,owl,nt.gz,ttl.gz,nq.gz,trig.gz,owl.gz,nt.bz2,ttl.bz2,nq.bz2,trig.bz2,owl.bz2")
    private String includeTypes;

    /** Folder in which to create the TDB2 database in and that will be archived. */
    @Parameter(defaultValue = "${project.build.directory}/tdb2")
    private File outputFolder;

    /** Output file. This is the archive of the outputFolder. */
    @Parameter(defaultValue = "${project.build.directory}/tdb2.tar.gz")
    private File outputFile;

    /** Output archive format. Invoke the 'list-archive-formats' goal to list available options. */
    @Parameter(defaultValue = "tar")
    private String outputFormat;

    /** Output archive format. Invoke the 'list-compression-formats' goal to list available options. */
    @Parameter(defaultValue = "gz")
    private String outputEncoding;

    /**
     * Set to true to scan dependencies in addition to specified sources.
     * By default, dependencies are scanned for RDF data only if there is no
     * &lt;sources&gt; element.
     */
    @Parameter(defaultValue = "false")
    private boolean alwaysScanDeps;

    /** Output file (the folder as an archive) */
//    @Parameter(defaultValue = "${project.build.directory}/tdb2.load.ttl")
//    private File loadStateFile;

    /** Mapping of (explicit) sources to graphs. */
    @Parameter
    private List<SourceToGraphMapping> sources = new ArrayList<>();

    /** Whether to skip creation of the archive from the database folder */
    @Parameter(property = "tdb2.archive.skip", defaultValue = "false")
    private boolean skipArchive;

    /**
     * Whether to skip the attachment of the created archive.
     * If skipped, then the archive will not become an artifact of this project.
     */
    @Parameter(property = "tdb2.attach.skip", defaultValue = "false")
    private boolean skipAttach;

    /** Output format */
//    @Parameter
//    private String outputFormat;

    @Override
    public void execute() throws MojoExecutionException {
        if (!skip) {
            JenaMojoHelper.execJenaBasedMojo(this::executeActual);
        }
    }

    public void executeActual() throws Exception {
        Log logger = getLog();

        boolean scanDeps = alwaysScanDeps || sources.isEmpty();

        Path outputPath = outputFolder.toPath();
        Location location = Location.create(outputPath);

        // Test creation first before resolving dependencies
                {
            DatasetGraph dg = TDB2Factory.connectDataset(location).asDatasetGraph();
            dg.close();
        }

        // Add project dependencies to the request.
        DependencyFilter classpathFlter = DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE);
        CollectRequest collectRequest = new CollectRequest();

        if (scanDeps) {
            for (Dependency dep : project.getDependencies()) {
                collectRequest.addDependency(AetherUtils.convert(dep));
            }
        }

        // Add artifacts referenced by the file-to-graph mapping.
        // XXX Scope should probably be ignored or set to compile!
        // Map<Artifact, List<FileToGraphMapping>> sourceArtifacts = new LinkedHashSet<>();
        Set<String> sourceArtifactIds = new LinkedHashSet<>();
        for (SourceToGraphMapping mapping : sources) {
            Dependency dep = mapping.getDependency();
            if (dep != null) {
                dep = mapping.getDependency();
                if (dep != null) {
                    if (mapping.getFile() != null) {
                        throw new RuntimeException("dependency and file are mutually exclusive: " + mapping);
                    }

                    Artifact ma = PomUtils.toArtifact(dep);
                    sourceArtifactIds.add(PomUtils.toString(ma));
                    collectRequest.addDependency(AetherUtils.convert(dep));
                }
            }
        }
        collectRequest.setRepositories(projectRepos);
        DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, classpathFlter);
        DependencyResult dependencyResult = repoSystem.resolveDependencies(repoSession, dependencyRequest);

        Map<String, org.eclipse.aether.artifact.Artifact> resolvedArtifacts = new LinkedHashMap<>();

        Set<String> includeTypeSet = new HashSet<>(Arrays.asList(includeTypes.split(",")));
        List<UpdateLoad> workloads = new ArrayList<>();
        for (ArtifactResult artifactResult : dependencyResult.getArtifactResults()) {

            org.eclipse.aether.artifact.Artifact artifact = artifactResult.getArtifact();
            Artifact ma = AetherUtils.convert(artifact);
            String artifactId = PomUtils.toString(ma);
            resolvedArtifacts.put(artifactId, artifact);

            boolean isSourceArtifact = sourceArtifactIds.contains(artifactId);
            if (!isSourceArtifact) {
                String extension = artifact.getExtension();

                boolean accept = false;
                for (String suffix : includeTypeSet) {
                    if (extension.endsWith(suffix)) {
                        int l = extension.length();
                        String tmp = extension.substring(0, l - suffix.length());
                        accept = tmp.isEmpty() || tmp.endsWith(".");
                    }
                }

                if (!accept) {
                    logger.debug("Ignoring " + artifact);
                    continue;
                }

                File artifactFile = artifactResult.getArtifact().getFile();
                String artifactPath = artifactFile.getAbsolutePath();

                String graphName = "urn:mvn:" + PomUtils.toString(AetherUtils.convert(artifact));

                logger.info("Selecting TDB2 workload: " + artifactPath + " -> " + graphName);

                UpdateLoad update = new UpdateLoad(artifactPath, graphName);
                workloads.add(update);
            }
        }

        for (SourceToGraphMapping mapping : sources) {
            String graphName = mapping.getGraph();
            List<String> rawGraphs = mapping.getGraphs();

            if (graphName != null && !rawGraphs.isEmpty()) {
                throw new IllegalArgumentException("graph and graphs are mutually exclusive. Got: graph = [" + graphName + "], graphs: " + rawGraphs);
            }

            List<String> graphs = rawGraphs.isEmpty() ? List.of(graphName) : rawGraphs;
            String art = null;
            if (mapping.getDependency() != null) {
                Artifact ma = PomUtils.toArtifact(mapping.getDependency());
                art = PomUtils.toString(ma);
            }
            File file = mapping.getFile();
            if (file == null) {
                getLog().info("aetherArt: " + art);
                org.eclipse.aether.artifact.Artifact aetherArtifact = resolvedArtifacts.get(art);
                file = aetherArtifact.getFile();
            }

            String pathStr = file.getAbsolutePath();

            for (String graph : graphs) {
                String finalGraph = graph == null || graph.isBlank()
                    ? null
                    : graph.equals("ARTIFACT") ? "urn:mvn:" + art : graph;

                Node graphNode = finalGraph	== null ? null : NodeFactory.createURI(finalGraph);
                UpdateLoad update = new UpdateLoad(pathStr, graphNode);
                String graphNodeLabel = getGraphLabel(graphNode);
                logger.info("Selecting TDB2 workload: " + pathStr + " -> " + graphNodeLabel);
                workloads.add(update);
            }
        }

        // XXX This is a simple change detection procedure that needs to evolve in the future.
        // It does not handle the case where multiple files are mapped to the same graph.
        // In general, we need to create a metadata file, tdb instance or graph to do the book-keeping

        String loadStateIri = "urn:load-state";
        LoadState loadState;
        Path loadStatePath = outputPath.resolveSibling("tdb2.loadstate.ttl");
        if (Files.exists(loadStatePath)) {
            Model loadStateModel = RDFDataMgr.loadModel(loadStatePath.toAbsolutePath().toString());
            loadState = loadStateModel.createResource(loadStateIri).as(LoadState.class);
        } else {
            loadState = ModelFactory.createDefaultModel().createResource(loadStateIri).as(LoadState.class);
        }

        boolean[] change = {false};
        Dataset dataset = TDB2Factory.connectDataset(location);
        try {
            DatasetGraph dg = dataset.asDatasetGraph();

            // Run load statements.
            for (UpdateLoad update : workloads) {
                String source = update.getSource();
                Node destNode = update.getDest();

                Node nonNullDestNode = destNode == null ? Quad.defaultGraphIRI : destNode;

                String destNodeLabel = getGraphLabel(destNode);
                FileState fs = loadState.getFileStates().computeIfAbsent(source, s -> {
                    FileState r = loadState.getModel().createResource().as(FileState.class);
                    r.setFileName(s);
                    return r;
                });

                Set<Node> loadedGraphs = Optional.ofNullable(fs).map(FileState::getGraphs).orElse(Collections.emptySet());
                boolean isAlreadyLoaded = loadedGraphs.contains(nonNullDestNode);

                if (isAlreadyLoaded) {
                    logger.info("Skipping TDB2 workload (already loaded): " + source + " -> " + destNodeLabel);
                } else {
                    logger.info("Executing TDB2 workload: " + source + " -> " + destNodeLabel);
                    fs.getGraphs().add(nonNullDestNode);

                    Txn.executeWrite(dg, () -> {
                        UpdateExec.dataset(dg).update(update).execute();
                        try {
                            FileUtils.safeCreate(loadStatePath, OverwritePolicy.OVERWRITE, out -> {
                                RDFDataMgr.write(out, loadState.getModel(), RDFFormat.TURTLE_PRETTY);
                            });
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }

                        change[0] = true;
                    });
                }
            }

            if (false) {
                { // Build tdb2 stats
                }

                { // Build spatial index
                    Path spatialIndexPath = outputPath.resolve("spatial.index");
                    String srsUri = null;
                    if (srsUri == null) {
                        String srsURI = GeoSPARQLOperations.findModeSRS(dataset);
                    }
                    SpatialIndex.buildSpatialIndex(dataset, srsUri, spatialIndexPath.toFile());

                    // TODO Add to file set
                }

                // Text index creation would require an assembler block
                // But running the service would have to be consistent - so perhaps its better
                // to not support text indexing here.
            }

        } finally {
            dataset.close();
            // StoreConnection.release(location);
            // Not sure whether there is a clean way to remove the lock files
            Files.deleteIfExists(outputPath.resolve("tdb.lock"));
            Files.deleteIfExists(outputPath.resolve("Data-0001/tdb.lock"));
            // ProcessFileLock lock = DatabaseConnection.lockForLocation(TDB2Factory.location(dataset));
//            Path path = lock.getPath();
//            logger.info("TDB2 file lock is: " + path);
//
//            ProcessFileLock.release(lock);
//            Files.delete(path);
        }

        if (!skipArchive) {
            Path outputFolderPath = outputFolder.toPath().toAbsolutePath();

            Path tgtFile = outputFile.toPath().toAbsolutePath();
            Path tgtTmpFile = tgtFile.resolveSibling("." + tgtFile.getFileName().toString());

            if (Files.exists(tgtFile) && !change[0]) {
                logger.info("No changes detected. Archive already exists: " + tgtFile);
            } else {
                Path relPath = project.getFile().toPath().getParent();
                logger.info("Writing temp archive: " + tgtTmpFile);
                logger.info("Shown paths are relative to: " + relPath);

                Map<String, Path> fileSet = createTdb2FileSet(outputFolderPath);
                packageTdb2(logger::info, tgtTmpFile, fileSet, relPath);
                atomicMoveOrCopy(logger::warn, tgtTmpFile, tgtFile);
                logger.info("Created archive: " + tgtFile);
            }

            if (!skipAttach) {
                mavenProjectHelper.attachArtifact(project, "tdb2.tar.gz", outputFile);
            }
        }
    }

    public static String getGraphLabel(Node graphNode) {
        String result = graphNode == null ? "(default graph)" : graphNode.toString();
        return result;
    }

    public static void atomicMoveOrCopy(Consumer<String> warnCallback, Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            warnCallback.accept(String.format("Atomic move from %s to %s failed, falling back to copy", source, target));
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Map<String, Path> createTdb2FileSet(Path folderToPackage) throws IOException {
        Map<String, Path> result = new LinkedHashMap<>();
        Files.walkFileTree(folderToPackage, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Skip lock files
                if (!file.getFileName().toString().endsWith(".lock")) {
                    Path relPath =  folderToPackage.relativize(file);
                    result.put(relPath.toString(), file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw new RuntimeException(exc);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    public static long totalSize(Iterator<Path> files) throws IOException {
        long result = 0;
        while (files.hasNext()) {
            Path path = files.next();
            result += Files.size(path);
        }
        return result;
    }

    private static class Tracker {
        int    maxDataPoints = 10;
        Deque<Entry<Long, Long>> timeAndTotalProgress = new ArrayDeque<>(maxDataPoints);


        long   totalSize = -1;
        int    fileCount = -1;
        long   currentFileStartProgress = 0; // Progress before the current file
        LongSupplier currentFileProgress;
        // totalProgress = currentFileStartProgress + currentFileProgress.getAsLong()

        String currentFileName = null;
        int    currentFileIdx = 0;
        long   currentFileSize = -1;
        // long   currentFileProgress = -1;
    }

    public <V> Entry<String, V> find(Map<String, V> map, String name) {
        Entry<String, V> entry = map.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(name))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("No enntry found for: " + name));
        return entry;
    }

    public void packageTdb2(Consumer<String> fileCallback, Path fileToWrite, Map<String, Path> fileSet, Path basePath) throws IOException, ArchiveException {

        Entry<String, ArchiveStreamProvider> archiverEntry = find(ArchiveStreamFactory.findAvailableArchiveOutputStreamProviders(), outputFormat);
        String archiverName = archiverEntry.getKey();
        ArchiveStreamProvider archiverProvider = archiverEntry.getValue();

        Function<OutputStream, OutputStream> encoder;
        if (outputEncoding == null || outputEncoding.isBlank()) {
            encoder = x -> x;
        } else {
            Entry<String, CompressorStreamProvider> encoderEntry = find(CompressorStreamFactory.findAvailableCompressorOutputStreamProviders(), outputEncoding);
            String encoderName = encoderEntry.getKey();
            CompressorStreamProvider encodingProvider = encoderEntry.getValue();
            encoder = x -> {
                try {
                    return encodingProvider.createCompressorOutputStream(encoderName, x);
                } catch (CompressorException e) {
                    throw new RuntimeException(e);
                }
            };
        }


        // String fileExtensionn = ".tar.gz";

        try (OutputStream fOut = Files.newOutputStream(fileToWrite);
            BufferedOutputStream buffOut = new BufferedOutputStream(fOut);
            OutputStream encodedOut = encoder.apply(buffOut);
            ArchiveOutputStream<? extends ArchiveEntry> tOut = archiverProvider.createArchiveOutputStream(archiverName, encodedOut, null)) {

            // Allow archiving large files.
            if (tOut instanceof TarArchiveOutputStream tarOut) {
                tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
                tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            }

            Tracker tracker = new Tracker();
            tracker.totalSize = totalSize(fileSet.values().iterator());
            tracker.fileCount = fileSet.size();

            // long startTime = System.currentTimeMillis();
            Runnable monitorProgress = () -> {
                long elapsedTime = System.currentTimeMillis();
                long currentFileProgress = tracker.currentFileProgress.getAsLong();
                long totalProgress = tracker.currentFileStartProgress + currentFileProgress;

                float fileRatio = tracker.currentFileSize == 0
                    ? 1.0f
                    : currentFileProgress / (float)tracker.currentFileSize;

                float totalRatio = tracker.totalSize == 0
                    ? 1.0f
                    : totalProgress / (float)tracker.totalSize;

                Deque<Entry<Long, Long>> points = tracker.timeAndTotalProgress;
                if (points.size() >= tracker.maxDataPoints) {
                    points.removeFirst();
                }
                Entry<Long, Long> newEntry = Map.entry(elapsedTime, totalProgress);
                points.addLast(newEntry);
                Entry<Long, Long> oldEntry = points.getFirst();

                float relDuration = (newEntry.getKey() - oldEntry.getKey()) * 0.001f; // ms to seconds
                long relAmount = newEntry.getValue() - oldEntry.getValue();
                float throughput = relDuration < 0.001f ? 0f : relAmount / relDuration;

                long remaining = tracker.totalSize - totalProgress;
                long etaInSeconds = throughput < 0.001f ? Long.MAX_VALUE : (long)(remaining / throughput);
                if (etaInSeconds == 0 && remaining > 0) {
                    etaInSeconds = 1;
                }
                String etaStr = etaInSeconds == Long.MAX_VALUE
                        ? "infinite"
                        : toString(Duration.ofSeconds(etaInSeconds));

                String msg = String.format("Adding file %d/%d %s %.2f%% - Total %.2f%% - ETA %s",
                        tracker.currentFileIdx, tracker.fileCount, tracker.currentFileName,
                        fileRatio * 100.0f, totalRatio * 100.0f, etaStr);

                fileCallback.accept(msg);
            };

            for (Entry<String, Path> e : fileSet.entrySet()) {
                String relPathStr = e.getKey();
                Path file = e.getValue();
                Path displayPath = basePath == null ? file : basePath.relativize(file);

                ++tracker.currentFileIdx;
                tracker.currentFileName = displayPath.toString();
                tracker.currentFileSize = Files.size(file);

                // TarArchiveEntry tarEntry = new TarArchiveEntry(file, relPathStr);
                ArchiveEntry archiveEntry = tOut.createArchiveEntry(file, relPathStr);
                ((ArchiveOutputStream<ArchiveEntry>)tOut).putArchiveEntry(archiveEntry);

                ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
                try (CountingInputStream cin = new CountingInputStream(Files.newInputStream(file))) {
                    tracker.currentFileProgress = () -> cin.getCount();
                    ScheduledFuture<?> future = ses.scheduleAtFixedRate(monitorProgress, 1, 10, TimeUnit.SECONDS);
                    try {
                        cin.transferTo(tOut);
                    } finally {
                        future.cancel(false);
                    }
                } finally {
                    ses.shutdown();
                    try {
                        if (!ses.awaitTermination(5, TimeUnit.SECONDS)) {
                            throw new RuntimeException("Progress monitor: Failed to stop.");
                        }
                    } catch (InterruptedException e1) {
                        throw new RuntimeException("Progress monitor: Unexpected interruption", e1);
                    }
                }
                monitorProgress.run();
                tracker.currentFileStartProgress += tracker.currentFileProgress.getAsLong();
                tOut.closeArchiveEntry();
            }
            tOut.finish();
        }
    }

    public static String toString(Duration eta) {
        int s = eta.toSecondsPart();
        int m = eta.toMinutesPart();
        int h = eta.toHoursPart();
        long d = eta.toDaysPart();

        StringBuilder b = new StringBuilder();
        if (d != 0) {
            if (!b.isEmpty()) { b.append(" "); }
            b.append(d).append("d");
        }

        if (h != 0) {
            if (!b.isEmpty()) { b.append(" "); }
            b.append(h).append("h");
        }

        if (m != 0) { // d != 0 || h != 0 ||
            if (!b.isEmpty()) { b.append(" "); }
            b.append(m).append("m");
        }

        if (!b.isEmpty()) { b.append(" "); }
        b.append(s).append("s");
        return b.toString();
    }

    @Deprecated
    protected String toString(org.eclipse.aether.artifact.Artifact coord) {
        String t = coord.getExtension();
        String c = coord.getClassifier();

        String suffix =
                (t == null || t.isEmpty() ? "" : ":" + t) +
                (c == null || c.isEmpty() ? "" : ":" + c);

        String result = coord.getGroupId() + ":" + coord.getArtifactId() + ":" + coord.getVersion() + suffix;
        return result;
    }

    public Path relativizeAgainstPom(File file) {
        Path pom = project.getFile().toPath().getParent();
        Path result = pom.relativize(file.toPath());
        return result;
    }

    public Path resolveAgainstPom(Path path) {
        Path pom = project.getFile().toPath().getParent();
        Path result = pom.resolve(path);
        return result;
    }

    /** Copied from tdbstats.java in (jena-cmds). */
    private static StatsResults stats$(DatasetGraphTDB dsg, Node gn) {

        NodeTable nt = dsg.getTripleTable().getNodeTupleTable().getNodeTable();
        StatsCollectorNodeId stats = new StatsCollectorNodeId(nt);

        if ( gn == null ) {
            Iterator<Tuple<NodeId>> iter = dsg.getTripleTable().getNodeTupleTable().findAll();
            for ( ; iter.hasNext() ; ) {
                Tuple<NodeId> t = iter.next();
                stats.record(null, t.get(0), t.get(1), t.get(2));
            }
        } else {
            // If the union graph, then we need to scan all quads but with
            // uniqueness.
            boolean unionGraph = Quad.isUnionGraph(gn);
            NodeId gnid = null;
            if ( !unionGraph ) {
                gnid = nt.getNodeIdForNode(gn);
                if ( NodeId.isDoesNotExist(gnid) )
                    System.err.println("No such graph: " + gn);
                    // Log.warn(tdbstats.class, "No such graph: " + gn);
            }

            NodeTupleTable ntt = dsg.getQuadTable().getNodeTupleTable();
            Iterator<Tuple<NodeId>> iter = unionGraph
                ? SolverLibTDB.unionGraph(ntt)
                : ntt.find(gnid, null, null, null) ;
            for ( ; iter.hasNext() ; ) {
                Tuple<NodeId> t = iter.next();
                stats.record(t.get(0), t.get(1), t.get(2), t.get(3));
            }
        }
        return stats.results();
    }

}
