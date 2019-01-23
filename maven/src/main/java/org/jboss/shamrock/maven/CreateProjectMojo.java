/*
 *
 *   Copyright (c) 2016-2018 Red Hat, Inc.
 *
 *   Red Hat licenses this file to you under the Apache License, version
 *   2.0 (the "License"); you may not use this file except in compliance
 *   with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *   implied.  See the License for the specific language governing
 *   permissions and limitations under the License.
 */

package org.jboss.shamrock.maven;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.fusesource.jansi.Ansi;
import org.jboss.shamrock.maven.components.Prompter;
import org.jboss.shamrock.maven.components.SetupTemplates;
import org.jboss.shamrock.maven.components.dependencies.Extensions;
import org.jboss.shamrock.maven.utilities.MojoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.fusesource.jansi.Ansi.ansi;
import static org.jboss.shamrock.maven.utilities.MojoUtils.*;

/**
 * This goal helps in setting up Shamrock Maven project with shamrock-maven-plugin, with sensible defaults
 */
@Mojo(name = "create", requiresProject = false)
public class CreateProjectMojo extends AbstractMojo {

    private static final String JAVA_EXTENSION = ".java";

    public static final String PLUGIN_VERSION_PROPERTY_NAME = "shamrock.version";
    public static final String PLUGIN_VERSION_PROPERTY = "${" + PLUGIN_VERSION_PROPERTY_NAME + "}";

    public static final String PLUGIN_KEY = getPluginGroupId() + ":" + getPluginArtifactId();

    /**
     * The Maven project which will define and configure the shamrock-maven-plugin
     */
    @Parameter(defaultValue = "${project}")
    protected MavenProject project;

    @Parameter(property = "projectGroupId")
    private String projectGroupId;

    @Parameter(property = "projectArtifactId")
    private String projectArtifactId;

    @Parameter(property = "projectVersion", defaultValue = "1.0-SNAPSHOT")
    private String projectVersion;

    @Parameter(property = "shamrockVersion")
    private String shamrockVersion;

    @Parameter(property = "path", defaultValue = "/hello")
    protected String path;

    @Parameter(property = "className")
    private String className;

    @Parameter(property = "extensions")
    private List<String> extensions;

    @Component
    private Prompter prompter;

    @Component
    private SetupTemplates templates;

    @Component
    private Extensions ext;

    /**
     * Remote repositories used for the project.
     */
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly = true)
    private List<ArtifactRepository> repositories;

    /**
     * The current build session instance. This is used for plugin manager API calls.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Override
    public void execute() throws MojoExecutionException {
        File pomFile = project.getFile();

        Model model;
        // Create pom.xml if not
        if (pomFile == null || !pomFile.isFile()) {
            pomFile = createPomFileFromUserInputs();
            // The previous method has set project to the new created project.
            createDirectories();
            templates.generate(project, path, className, getLog());
            printUserInstructions(pomFile);
            return;
        }

        // There is an existing `pom.xml` file
        Optional<Plugin> maybe = MojoUtils.hasPlugin(project, PLUGIN_KEY);
        if (maybe.isPresent()) {
            getLog().info("The " + getPluginArtifactId() + " is already configured in the existing pom.xml, " +
                    " nothing to do.");
            return;
        }

        // We should get cloned of the original model, as project.getModel will return effective model
        model = project.getOriginalModel().clone();

        if (!isTypeSupported(model.getPackaging())) {
            throw new MojoExecutionException("Unable to add " + getPluginArtifactId() + " to the existing pom.xml " +
                    "file - unsupported project type: " + model.getPackaging());
        }

        // Ensure we don't have the project metadata provided by the user
        if (projectArtifactId != null  || projectGroupId != null) {
            throw new MojoExecutionException("Unable to add " + getPluginArtifactId() + " to the existing pom.xml " +
                    "file - you can't provide project GAV and extend an existing pom.xml");
        }

        addVersionProperty(model);
        addBom(model);
        addMainPluginConfig(model);
        ext.addExtensions(model, extensions, session, repositories, getLog());
        addNativeProfile(model);
        createDirectories();
        save(pomFile, model);
    }

    private boolean isTypeSupported(String packaging) {
        return packaging == null  || packaging.equalsIgnoreCase("jar");
    }

    private void addBom(Model model) {
        Dependency bom = new Dependency();
        bom.setArtifactId(MojoUtils.get("bom-artifactId"));
        bom.setGroupId(getPluginGroupId());
        bom.setVersion("${shamrock.version}"); // Use the variable.
        bom.setType("pom");
        bom.setScope("import");

        DependencyManagement dm = model.getDependencyManagement();
        if (dm == null) {
            dm = new DependencyManagement();
        }
        dm.addDependency(bom);
        model.setDependencyManagement(dm);
    }

    private void printUserInstructions(File pomFile) {
        getLog().info("");
        getLog().info("========================================================================================");
        getLog().info(ansi().a("Your new application has been created in ").bold().a(pomFile.getAbsolutePath()).boldOff().toString());
        getLog().info(ansi().a("Navigate into this directory and launch your application with ").bold().fg(Ansi.Color.CYAN).a("mvn compile shamrock:dev").reset().toString());
        getLog().info(ansi().a("Your application will be accessible on ").bold().fg(Ansi.Color.CYAN).a("http://localhost:8080").reset().toString());
        getLog().info("========================================================================================");
        getLog().info("");
    }

    private void addNativeProfile(Model model) {
        Profile profile = new Profile();
        profile.setId("native");
        BuildBase buildBase = new BuildBase();
        Plugin plg = plugin(getPluginGroupId(), getPluginArtifactId(), PLUGIN_VERSION_PROPERTY);
        PluginExecution exec = new PluginExecution();
        exec.addGoal("native-image");
        MojoUtils.Element element = new MojoUtils.Element("enableHttpUrlHandler", "true");
        exec.setConfiguration(configuration(element));
        plg.addExecution(exec);
        buildBase.addPlugin(plg);
        profile.setBuild(buildBase);
        model.addProfile(profile);
    }

    private void addMainPluginConfig(Model model) {
        Plugin plugin = plugin(getPluginGroupId(), getPluginArtifactId(), getPluginVersion());
        if (isParentPom(model)) {
            addPluginManagementSection(model, plugin);
            //strip the shamrockVersion off
            plugin = plugin(getPluginGroupId(), getPluginArtifactId());
        } else {
            plugin = plugin(getPluginGroupId(), getPluginArtifactId(), PLUGIN_VERSION_PROPERTY);
        }
        PluginExecution pluginExec = new PluginExecution();
        pluginExec.addGoal("build");
        plugin.addExecution(pluginExec);
        Build build = createBuildSectionIfRequired(model);
        build.getPlugins().add(plugin);
    }

    private void addVersionProperty(Model model) {
        //Set  a property at maven project level for Shamrock maven plugin versions
        shamrockVersion = shamrockVersion == null ? getPluginVersion() : shamrockVersion;
        model.getProperties().putIfAbsent(PLUGIN_VERSION_PROPERTY_NAME, shamrockVersion);
    }

    private Build createBuildSectionIfRequired(Model model) {
        Build build = model.getBuild();
        if (build == null) {
            build = new Build();
            model.setBuild(build);
        }
        if (build.getPlugins() == null) {
            build.setPlugins(new ArrayList<>());
        }
        return build;
    }

    private void addPluginManagementSection(Model model, Plugin plugin) {
        if (model.getBuild().getPluginManagement() != null) {
            if (model.getBuild().getPluginManagement().getPlugins() == null) {
                model.getBuild().getPluginManagement().setPlugins(new ArrayList<>());
            }
            model.getBuild().getPluginManagement().getPlugins().add(plugin);
        }
    }

    private File createPomFileFromUserInputs() throws MojoExecutionException {
        Model model;
        String workingdDir = System.getProperty("user.dir");
        File pomFile = new File(workingdDir, "pom.xml");
        try {

            if (projectGroupId == null) {
                projectGroupId = prompter.promptWithDefaultValue("Set the project groupId",
                        "io.jboss.shamrock.sample");
            }

            // If the user does not specify the artifactId, we switch to the interactive mode.
            if (projectArtifactId == null) {
                projectArtifactId = prompter.promptWithDefaultValue("Set the project artifactId",
                        "my-shamrock-project");

                // Ask for version only if we asked for the artifactId
                projectVersion = prompter.promptWithDefaultValue("Set the project version", "1.0-SNAPSHOT");

                // Ask for maven version if not set
                if (shamrockVersion == null) {
                    shamrockVersion = prompter.promptWithDefaultValue("Set the Shamrock version",
                            getPluginVersion());
                }

                if (className == null) {
                    className = prompter.promptWithDefaultValue("Set the resource class name",
                            projectGroupId.replace("-", ".").replace("_", ".")
                                    + ".HelloResource");

                    if (className != null && className.endsWith(JAVA_EXTENSION)) {
                        className = className.substring(0, className.length() - JAVA_EXTENSION.length());
                    }
                }

                if (path == null) {
                    path = prompter.promptWithDefaultValue("Set the resource path ",
                            "/hello");
                    if (!path.startsWith("/")) {
                        path = "/" + path;
                    }
                }
            }

            // Create directory if the current one is not empty.
            File wkDir = new File(workingdDir);
            String[] children = wkDir.list();
            if (children != null && children.length != 0) {
                // Need to generate directory
                File sub = new File(wkDir, projectArtifactId);
                sub.mkdirs();
                getLog().info("Directory " + projectArtifactId + " created");
                // This updates the project pom file but also the base directory.
                pomFile = new File(sub, "pom.xml");
                project.setFile(pomFile);
            }


            Map<String, String> context = new HashMap<>();
            context.put("project_groupId", projectGroupId);
            context.put("project_artifactId", projectArtifactId);
            context.put("project_version", projectVersion);
            context.put("shamrock_version", shamrockVersion != null ? shamrockVersion : getPluginVersion());
            context.put("class_name", className);
            context.put("path", path);

            templates.createNewProjectPomFile(context, pomFile);
            templates.createIndexPage(context, project.getBasedir(), getLog());
            templates.createDockerFile(context, project.getBasedir(), getLog());
            templates.createConfiguration(project.getBasedir(), getLog());

            //The project should be recreated and set with right model
            MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();

            model = xpp3Reader.read(new FileInputStream(pomFile));
        } catch (Exception e) {
            throw new MojoExecutionException("Error while setup of shamrock-maven-plugin", e);
        }

        project = new MavenProject(model);
        project.setFile(pomFile);
        project.setPomFile(pomFile);
        project.setOriginalModel(model); // the current model is the original model as well

        ext.addExtensions(model, extensions, session, repositories, getLog());
        save(pomFile, model);
        return pomFile;
    }


    private void save(File pomFile, Model model) throws MojoExecutionException {
        MavenXpp3Writer xpp3Writer = new MavenXpp3Writer();
        try (FileWriter pomFileWriter = new FileWriter(pomFile)) {
            xpp3Writer.write(pomFileWriter, model);
            pomFileWriter.flush();
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to write the pom.xml file", e);
        }
    }

    private void createDirectories() {
        File base = project.getBasedir();
        File source = new File(base, "src/main/java");
        File resources = new File(base, "src/main/resources");
        File test = new File(base, "src/test/java");

        String prefix = "Creation of ";
        if (!source.isDirectory()) {
            boolean res = source.mkdirs();
            getLog().debug(prefix + source.getAbsolutePath() + " : " + res);
        }
        if (!resources.isDirectory()) {
            boolean res = resources.mkdirs();
            getLog().debug(prefix + resources.getAbsolutePath() + " : " + res);
        }
        if (!test.isDirectory()) {
            boolean res = test.mkdirs();
            getLog().debug(prefix + test.getAbsolutePath() + " : " + res);
        }
    }

    private boolean isParentPom(Model model) {
        return "pom".equals(model.getPackaging());
    }


}