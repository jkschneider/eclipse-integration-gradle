package org.springsource.ide.eclipse.gradle.core.ivy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.GradleModuleVersion;
import org.springsource.ide.eclipse.gradle.core.GradleCore;
import org.springsource.ide.eclipse.gradle.core.GradleModelProvider;
import org.springsource.ide.eclipse.gradle.core.GradleProject;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

public class IvyUtils {
	static LoadingCache<GradleProject, GradleModuleVersion> moduleVersionCache = CacheBuilder.newBuilder()
		.build(new CacheLoader<GradleProject, GradleModuleVersion>() {
            public GradleModuleVersion load(GradleProject project) throws CoreException {
            	ProjectConnection gradleConnector = GradleModelProvider.getGradleConnector(project, null);
            	gradleConnector.newBuild().forTasks("generateDescriptorFileForIvyPublication").run();
            	
            	BufferedReader reader = null;
	            try {
					String projectPath = project.getProject().getFullPath().toFile().getPath();

					reader = new BufferedReader(new FileReader(new File(projectPath + "/build/publications/ivy/ivy.xml")));
					String line;
					while((line = reader.readLine()) != null) {
						if(line.contains("<info")) {
							Pattern pattern = Pattern.compile("organisation=\"([^\"]*)\"");
							Matcher matcher = pattern.matcher(line);
							matcher.find();
							final String org = matcher.group(1);
							
							pattern = Pattern.compile("module=\"([^\"]+)\"");
							matcher = pattern.matcher(line);
							matcher.find();
							final String module = matcher.group(1);
							
							return new GradleModuleVersion() {
								@Override public String getVersion() { return null; }
								@Override public String getName() { return module; }
								@Override public String getGroup() { return org; }
							};
						}
					}
				}
	            catch (IOException e) { }
	            finally {
	            	if(reader != null) {
	            		try {
							reader.close();
						} catch (IOException e) {
						}
	            	}
	            }
            	
            	return null;
            }
        });
	
	public static GradleModuleVersion gradleModuleVersion(GradleProject project) {
		try {
			return moduleVersionCache.get(project);
		} catch (ExecutionException e) {
			// unable to open project connection for this project, return null
		}
		
		return null;
	}
	
	public static IProject getIvyProject(ExternalDependency dep) {
		for (GradleProject gradleProject : GradleCore.getGradleProjects())
			if(moduleVersionMatches(gradleModuleVersion(gradleProject), dep.getGradleModuleVersion()))
				return gradleProject.getProject();
		return null;
	}
	
	private static boolean moduleVersionMatches(GradleModuleVersion v1, GradleModuleVersion v2) {
		return v1.getGroup().equals(v2.getGroup()) && v1.getName().equals(v2.getName());
	}
}
