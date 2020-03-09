/*
 * Copyright (C) 2012 Red Hat, Inc. (jcasey@redhat.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.maven.ext.core.impl;

import org.apache.maven.model.ConfigurationContainer;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Profile;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.ref.SimpleArtifactRef;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.common.util.ProfileUtils;
import org.commonjava.maven.ext.core.ManipulationSession;
import org.commonjava.maven.ext.core.state.DependencyState;
import org.commonjava.maven.ext.core.state.PluginState;
import org.commonjava.maven.ext.core.state.RelocationState;
import org.commonjava.maven.ext.core.state.State;
import org.commonjava.maven.ext.common.util.WildcardMap;
import org.commonjava.maven.ext.io.resolver.GalleyAPIWrapper;
import org.commonjava.maven.galley.maven.parse.GalleyMavenXMLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.commonjava.maven.ext.core.util.IdUtils.ga;

/**
 * {@link Manipulator} implementation that can relocation specified groupIds. It will also handle version changes by
 * delegating to dependencyExclusions.
 * Configuration is stored in a {@link PluginState} instance, which is in turn stored in the {@link ManipulationSession}.
 */
@Named("relocations-manipulator")
@Singleton
public class RelocationManipulator
        implements Manipulator
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private ManipulationSession session;

    private GalleyAPIWrapper galleyWrapper;

    @Inject
    public RelocationManipulator( GalleyAPIWrapper galleyWrapper )
    {
        this.galleyWrapper = galleyWrapper;
    }

    /**
     * Initialize the {@link PluginState} state holder in the {@link ManipulationSession}. This state holder detects
     * relocation configuration from the Maven user properties (-D properties from the CLI) and makes it available for
     * later.
     */
    @Override
    public void init( final ManipulationSession session )
                    throws ManipulationException
    {
        this.session = session;
        session.setState( new RelocationState( session.getUserProperties() ) );
    }

    /**
     * Apply the relocation changes to the list of {@link Project}'s given.
     */
    @Override
    public Set<Project> applyChanges( final List<Project> projects )
            throws ManipulationException
    {
        final State state = session.getState( RelocationState.class );

        if ( !session.isEnabled() || !state.isEnabled() )
        {
            logger.debug("{}: Nothing to do!", getClass().getSimpleName());
            return Collections.emptySet();
        }

        final Set<Project> changed = new HashSet<>();

        for ( final Project project : projects )
        {
            final Model model = project.getModel();

            if ( apply( project, model ) )
            {
                changed.add( project );
            }
        }

        return changed;
    }

    private boolean apply( final Project project, final Model model ) throws ManipulationException
    {
        boolean result = false;
        final RelocationState state = session.getState( RelocationState.class );
        final WildcardMap<ProjectVersionRef> relocations = state.getDependencyRelocations();

        logger.debug( "Applying relocation changes to: " + ga( project ) );

        DependencyManagement dependencyManagement = model.getDependencyManagement();
        if ( dependencyManagement != null )
        {
            result = updateDependencies( relocations, project.getResolvedManagedDependencies( session ) );
        }
        result |= updateDependencies( relocations, project.getAllResolvedDependencies( session ) );

        for ( final Profile profile : ProfileUtils.getProfiles( session, model) )
        {
            dependencyManagement = profile.getDependencyManagement();
            if ( dependencyManagement != null )
            {
                result |= updateDependencies( relocations, project.getResolvedProfileManagedDependencies( session ).get( profile ) );
            }
            result |= updateDependencies( relocations, project.getAllResolvedProfileDependencies( session ).get( profile ) );

        }

        result |= updatePlugins( relocations, project, project.getResolvedManagedPlugins( session ) );

        result |= updatePlugins( relocations, project, project.getResolvedPlugins( session ) );

        for ( Profile profile : project.getResolvedProfilePlugins( session ).keySet() )
        {
            result |= updatePlugins( relocations, project, project.getResolvedProfilePlugins( session ).get( profile ) );
        }

        for ( Profile profile : project.getResolvedProfileManagedPlugins( session ).keySet() )
        {
            result |= updatePlugins( relocations, project, project.getResolvedProfileManagedPlugins( session ).get( profile ) );
        }

        return result;
    }

    private boolean updateDependencies( WildcardMap<ProjectVersionRef> relocations, Map<ArtifactRef, Dependency> dependencies )
    {
        final Map<ArtifactRef, Dependency> postFixUp = new HashMap<>();
        boolean result = false;

        // If we do a single pass over the dependencies that will handle the relocations *but* it will not handle
        // where one relocation alters the dependency and a subsequent relocation alters it again. For instance, the
        // first might wildcard alter the groupId and the second, more specifically alters one with the artifactId
        for ( int i = 0 ; i < relocations.size(); i++ )
        {
            Iterator<ArtifactRef> it = dependencies.keySet().iterator();
            while ( it.hasNext() )
            {
                final ArtifactRef pvr = it.next();
                if ( relocations.containsKey( pvr.asProjectRef() ) )
                {
                    ProjectVersionRef relocation = relocations.get( pvr.asProjectRef() );

                    updateDependencyExclusion( pvr, relocation );

                    Dependency dependency = dependencies.get( pvr );

                    logger.info( "Replacing groupId {} by {} and artifactId {} with {}",
                                 dependency.getGroupId(), relocation.getGroupId(), dependency.getArtifactId(), relocation.getArtifactId() );

                    if ( !relocation.getArtifactId().equals( WildcardMap.WILDCARD ) )
                    {
                        dependency.setArtifactId( relocation.getArtifactId() );
                    }
                    dependency.setGroupId( relocation.getGroupId() );

                    // Unfortunately because we iterate using the resolved project keys if the relocation updates those
                    // keys multiple iterations will not work. Therefore we need to remove the original key:dependency
                    // to map to the relocated form.
                    postFixUp.put( SimpleArtifactRef.parse( dependency.getManagementKey() ), dependency );
                    it.remove();

                    result = true;
                }
            }
            dependencies.putAll( postFixUp );
            postFixUp.clear();
        }
        return result;
    }

    private boolean updatePlugins( final WildcardMap<ProjectVersionRef> relocations, final Project project, final Map<ProjectVersionRef, Plugin> pluginMap ) throws ManipulationException
    {
        final List<PluginReference> refs =  findPluginReferences( project, pluginMap );
        final int size = relocations.size();

        logger.debug( "Got {} relocations for plugins", size );

        boolean result = false;

        for ( int i = 0; i < size; i++ )
        {
            final Iterator<PluginReference> it = refs.iterator();

            while ( it.hasNext() )
            {
                final Dependency dependency = new Dependency();
                final PluginReference ref = it.next();

                dependency.setGroupId( ref.groupIdNode.getTextContent() );
                dependency.setArtifactId( ref.artifactIdNode.getTextContent() );

                if ( relocations.containsKey( dependency ) )
                {
                    final ProjectVersionRef relocation = relocations.get( dependency );

                    ref.groupIdNode.setTextContent( relocation.getGroupId() );

                    if ( !relocation.getArtifactId().equals( WildcardMap.WILDCARD ) )
                    {
                        ref.artifactIdNode.setTextContent( relocation.getArtifactId() );
                    }

                    if ( ref.versionNode != null && !relocation.getVersionString().equals( WildcardMap.WILDCARD ) )
                    {
                        ref.versionNode.setTextContent( relocation.getVersionString() );
                    }

                    ref.container.setConfiguration( getConfigXml( ref.groupIdNode ) );

                    result = true;
                }
            }
        }

        return result;
    }

    /**
     * @param depPvr the resolved dependency we are processing the exclusion for.
     * @param relocation Map containing the update information for relocations.
     */
    private void updateDependencyExclusion( ProjectVersionRef depPvr, ProjectVersionRef relocation )
    {
        final DependencyState state = session.getState( DependencyState.class );

        if (relocation.getVersionString().equals( WildcardMap.WILDCARD ) )
        {
            logger.debug ("No version alignment to perform for relocations");
        }
        else
        {
            String artifact = depPvr.getArtifactId();
            if ( ! relocation.getArtifactId().equals( WildcardMap.WILDCARD ))
            {
                artifact = relocation.getArtifactId();
            }

            logger.debug ("Adding dependencyOverride {} & {}", relocation.getGroupId() + ':' + artifact + "@*",
                          relocation.getVersionString() );
            state.updateExclusions( relocation.getGroupId() + ':' + artifact + "@*", relocation.getVersionString() );
        }
    }

    private PluginReference findPluginReference( final Map.Entry<ConfigurationContainer, String> entry, final NodeList children )
    {
        logger.debug( "Got {} children to update plugin GAVs", children.getLength() );

        final int length = children.getLength();
        Node groupIdNode = null;
        Node artifactIdNode = null;
        Node versionNode = null;

        for ( int i = 0; i < length; i++ )
        {
            final Node node = children.item( i );

            if ( node.getNodeName().equals( "groupId" ) )
            {
                groupIdNode = node;
            }
            else if ( node.getNodeName().equals( "artifactId" ) )
            {
                artifactIdNode = node;
            }
            else if ( node.getNodeName().equals( "version" ) )
            {
                versionNode = node;
            }
        }

        if ( groupIdNode != null && artifactIdNode != null )
        {
            PluginReference ref = new PluginReference( entry.getKey(), groupIdNode, artifactIdNode, versionNode );

            logger.debug( "Found plugin reference: {}", ref );

            return ref;
        }

        return null;
    }

    public List<PluginReference> findPluginReferences( final Project project, Map<ProjectVersionRef, Plugin> pluginMap ) throws ManipulationException
    {
        final Collection<Plugin> plugins = pluginMap.values();
        final List<PluginReference> refs = new ArrayList<>();
        final String[] expressions = new String[] { ".//*" , ".//artifactItems/artifactItem" };

        logger.debug( "Found {} plugins in POM", plugins.size() );

        for ( final Plugin plugin : plugins )
        {
            final Map<ConfigurationContainer, String> configs = findConfigurations( plugin );

            logger.debug( "Found {} configs for plugin {}:{}:{}", configs.size(), plugin.getGroupId(), plugin.getArtifactId(), plugin.getVersion() );

            for ( final Map.Entry<ConfigurationContainer, String> entry : configs.entrySet() )
            {
                try
                {
                    final Document doc = galleyWrapper.parseXml( entry.getValue() );
                    final XPath xPath = XPathFactory.newInstance().newXPath();

                    for ( String expression : expressions )
                    {
                        final NodeList children = (NodeList) xPath.evaluate( expression, doc, XPathConstants.NODESET );
                        final PluginReference ref = findPluginReference( entry, children );

                        if ( ref != null )
                        {
                            refs.add( ref );
                        }
                    }
                }
                catch ( final GalleyMavenXMLException e )
                {
                    throw new ManipulationException( "Unable to parse config for plugin {} in {}", plugin.getId(), project.getKey(), e );
                }
                catch ( final  XPathExpressionException e )
                {
                    throw new ManipulationException( "Invalid XPath expression for plugin {} in {}", plugin.getId(), project.getKey(), e );
                }
            }
        }

        return refs;
    }

    private Map<ConfigurationContainer, String> findConfigurations( final Plugin plugin )
    {
        if ( plugin == null )
        {
            return Collections.emptyMap();
        }

        final Map<ConfigurationContainer, String> configs = new LinkedHashMap<>();
        Object configuration = plugin.getConfiguration();

        if ( configuration != null )
        {
            configs.put( plugin, configuration.toString() );
        }

        final List<PluginExecution> executions = plugin.getExecutions();

        if ( executions != null )
        {
            for ( PluginExecution execution : executions )
            {
                configuration = execution.getConfiguration();

                if ( configuration != null )
                {
                    configs.put( execution, configuration.toString() );
                }
            }
        }

        return configs;
    }

    private Xpp3Dom getConfigXml( final Node node ) throws ManipulationException
    {
        final String config = galleyWrapper.toXML( node.getOwnerDocument(), false ).trim();

        try
        {
            return Xpp3DomBuilder.build( new StringReader( config ) );
        }
        catch ( final XmlPullParserException | IOException e )
        {
            throw new ManipulationException( "Failed to re-parse plugin configuration into Xpp3Dom: {}. Config was: {}", e.getMessage(), config, e );
        }
    }

    @Override
    public int getExecutionIndex()
    {
        return 15;
    }

    private final class PluginReference
    {
        private final ConfigurationContainer container;

        private final Node groupIdNode;

        private final Node artifactIdNode;

        private final Node versionNode;

        PluginReference( final ConfigurationContainer container, final Node groupIdNode, final Node artifactIdNode,
                         final Node versionNode )
        {
            this.container = container;
            this.groupIdNode = groupIdNode;
            this.artifactIdNode = artifactIdNode;
            this.versionNode = versionNode;
        }
    }
}
