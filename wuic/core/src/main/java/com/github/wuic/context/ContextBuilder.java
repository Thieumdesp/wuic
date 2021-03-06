/*
 * "Copyright (c) 2015   Capgemini Technology Services (hereinafter "Capgemini")
 *
 * License/Terms of Use
 * Permission is hereby granted, free of charge and for the term of intellectual
 * property rights on the Software, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to use, copy, modify and
 * propagate free of charge, anywhere in the world, all or part of the Software
 * subject to the following mandatory conditions:
 *
 * -   The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * Any failure to comply with the above shall automatically terminate the license
 * and be construed as a breach of these Terms of Use causing significant harm to
 * Capgemini.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, PEACEFUL ENJOYMENT,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
 * OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * Except as contained in this notice, the name of Capgemini shall not be used in
 * advertising or otherwise to promote the use or other dealings in this Software
 * without prior written authorization from Capgemini.
 *
 * These Terms of Use are subject to French law.
 *
 * IMPORTANT NOTICE: The WUIC software implements software components governed by
 * open source software licenses (BSD and Apache) of which CAPGEMINI is not the
 * author or the editor. The rights granted on the said software components are
 * governed by the specific terms and conditions specified by Apache 2.0 and BSD
 * licenses."
 */


package com.github.wuic.context;

import com.github.wuic.NutType;
import com.github.wuic.ProcessContext;
import com.github.wuic.Workflow;
import com.github.wuic.WorkflowTemplate;
import com.github.wuic.config.ObjectBuilder;
import com.github.wuic.config.ObjectBuilderFactory;
import com.github.wuic.config.ObjectBuilderInspector;
import com.github.wuic.engine.Engine;
import com.github.wuic.engine.EngineService;
import com.github.wuic.engine.HeadEngine;
import com.github.wuic.engine.NodeEngine;
import com.github.wuic.exception.WorkflowTemplateNotFoundException;
import com.github.wuic.exception.WuicException;
import com.github.wuic.nut.HeapListener;
import com.github.wuic.nut.Nut;
import com.github.wuic.nut.dao.NutDao;
import com.github.wuic.nut.dao.NutDaoService;
import com.github.wuic.nut.NutsHeap;
import com.github.wuic.nut.dao.core.ClasspathNutDao;
import com.github.wuic.nut.dao.core.ProxyNutDao;
import com.github.wuic.nut.filter.NutFilter;
import com.github.wuic.nut.filter.NutFilterHolder;
import com.github.wuic.nut.filter.NutFilterService;
import com.github.wuic.util.CollectionUtils;
import com.github.wuic.util.IOUtils;
import com.github.wuic.util.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p>
 * This builder can be configured to build contexts in an expected state by the user. It is designed to be used in a
 * multi-threaded environment.
 * </p>
 *
 * <p>
 * The builder tracks all settings by associating to them a tag. With that tag, the user is able to delete all settings
 * defined at a moment using the {@link #clearTag(Object)} method. To check if a setting has changed, you can rely on
 * the {@link com.github.wuic.context.Context#isUpToDate()} method.
 *
 * <p>
 * If any operation is performed without any tag, then an exception will be thrown. Moreover, when the
 * {@link ContextBuilder#tag(Object)} method is called, the current threads holds a lock on the object.
 * It will be released when the {@link ContextBuilder#releaseTag()} will be called.
 * Consequently, it is really important to always call this last method in a finally block.
 * </p>
 *
 * @author Guillaume DROUET
 * @version 1.3
 * @since 0.4.0
 */
public class ContextBuilder extends Observable {

    /**
     * Prefix for default IDs.
     */
    static final String BUILDER_ID_PREFIX = "wuicDefault";

    /**
     * The logger.
     */
    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * The internal lock for tags.
     */
    private ReentrantLock lock;

    /**
     * The current tag.
     */
    private Object currentTag;

    /**
     * All settings associated to their tag.
     */
    private TaggedSettings taggedSettings;

    /**
     * Factory for engine builder.
     */
    private ObjectBuilderFactory<Engine> engineBuilderFactory;

    /**
     * Factory for nut dao builder.
     */
    private ObjectBuilderFactory<NutDao> nutDaoBuilderFactory;

    /**
     * Factory for nut filter builder.
     */
    private ObjectBuilderFactory<NutFilter> nutFilterBuilderFactory;

    /**
     * A property file URL containing properties to apply to all registered components.
     */
    private URL propertySet;

    /**
     * Indicates that {@link #configureDefault()} has been called and default entries are injected.
     */
    private boolean configureDefault;

    /**
     * <p>
     * Creates a new instance with the builder factories of a context and additional inspectors.
     * </p>
     *
     * @param b the builder providing the components factories (engine, dao, filter)
     * @param properties the property set to apply to all components
     * @param inspectors the inspectors to add to the factories
     */
    public ContextBuilder(final ContextBuilder b, final URL properties, final ObjectBuilderInspector ... inspectors) {
        this(b.getEngineBuilderFactory(), b.getNutDaoBuilderFactory(), b.getNutFilterBuilderFactory(), inspectors);
        propertySet = properties;
    }

    /**
     * <p>
     * Creates a new instance with specific builder factories.
     * </p>
     *
     * @param engineBuilderFactory the engine builder factory, {@code null} if default should be created
     * @param nutDaoBuilderFactory the DAO builder factory, {@code null} if default should be created
     * @param nutFilterBuilderFactory the filter builder factory, {@code null} if default should be created
     * @param inspectors the inspectors to add to the factories
     */
    public ContextBuilder(final ObjectBuilderFactory<Engine> engineBuilderFactory,
                          final ObjectBuilderFactory<NutDao> nutDaoBuilderFactory,
                          final ObjectBuilderFactory<NutFilter> nutFilterBuilderFactory,
                          final ObjectBuilderInspector ... inspectors) {
        this.taggedSettings = new TaggedSettings();
        this.lock = new ReentrantLock();
        this.configureDefault = false;

        this.engineBuilderFactory = engineBuilderFactory == null ?
                new ObjectBuilderFactory<Engine>(EngineService.class, EngineService.DEFAULT_SCAN_PACKAGE) : engineBuilderFactory;
        this.nutDaoBuilderFactory = nutDaoBuilderFactory == null ?
                new ObjectBuilderFactory<NutDao>(NutDaoService.class, NutDaoService.DEFAULT_SCAN_PACKAGE) : nutDaoBuilderFactory;
        this.nutFilterBuilderFactory = nutFilterBuilderFactory == null ?
                new ObjectBuilderFactory<NutFilter>(NutFilterService.class, NutFilterService.DEFAULT_SCAN_PACKAGE) : nutFilterBuilderFactory;

        final ObjectBuilderInspector inspector = new NutFilterHolderInspector();
        this.engineBuilderFactory.inspector(inspector);
        this.nutDaoBuilderFactory.inspector(inspector);

        for (final ObjectBuilderInspector i : inspectors) {
            this.engineBuilderFactory.inspector(i);
            this.nutDaoBuilderFactory.inspector(i);
        }
    }

    /**
     * <p>
     * Creates a new instance.
     * </p>
     *
     * @param inspectors the inspectors
     */
    public ContextBuilder(final ObjectBuilderInspector ... inspectors) {
        this(null, null, null, inspectors);
    }

    /**
     * <p>
     * Sets the filters configured in the given instance if it's a {@link NutFilterHolder}.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 0.5.0
     */
    final class NutFilterHolderInspector implements ObjectBuilderInspector {

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> T inspect(final T object) {
            if (NutFilterHolder.class.isAssignableFrom(object.getClass())) {
                NutFilterHolder.class.cast(object).setNutFilter(getFilters());
            }

            return object;
        }
    }

    /**
     * <p>
     * This class configures by default builder's components from a particular {@link ObjectBuilderFactory} in the
     * {@link ContextBuilder}.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 0.5.2
     */
    abstract class DefaultContextBuilderConfigurator extends ContextBuilderConfigurator {

        /**
         * The object builder factory.
         */
        private final ObjectBuilderFactory<?> objectBuilderFactory;

        /**
         * <p>
         * Builds a new instance.
         * </p>
         *
         * @param obf the factory
         */
        private DefaultContextBuilderConfigurator(final ObjectBuilderFactory<?> obf) {
            objectBuilderFactory = obf;
        }

        /**
         * <p>
         * Applies default configuration for the given {@link com.github.wuic.config.ObjectBuilderFactory.KnownType}
         * to the specified builder.
         * </p>
         *
         * @param type the type
         * @param contextBuilder the builder
         */
        abstract void internalConfigure(ContextBuilder contextBuilder, ObjectBuilderFactory.KnownType type);

        /**
         * {@inheritDoc}
         */
        @Override
        public int internalConfigure(final ContextBuilder ctxBuilder) {
            for (final ObjectBuilderFactory.KnownType type : objectBuilderFactory.knownTypes()) {
                internalConfigure(ctxBuilder, type);
            }

            // Never poll
            return -1;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getTag() {
            return Arrays.deepToString(objectBuilderFactory.knownTypes().toArray());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected Long getLastUpdateTimestampFor(final String path) throws IOException {
            // Never poll
            return 1L;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ProcessContext getProcessContext() {
            return ProcessContext.DEFAULT;
        }
    }

    /**
     * <p>
     * A registration for a {@link NutsHeap} to be created when the context is built.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 0.5.1
     */
    final class HeapRegistration {

        /**
         * If the heap will be disposable.
         */
        private final boolean disposable;

        /**
         * The {@link NutDao} builder id.
         */
        private final String ndbId;

        /**
         * The paths not already filtered.
         */
        private final String[] paths;

        /**
         * The listener that will be notified by the heap.
         */
        private final HeapListener[] listeners;

        /**
         * The created heap.
         */
        private NutsHeap heap;

        /**
         * Some IDs of other heaps to create a composition.
         */
        private String[] heapIds;

        /**
         * The factory of the heap.
         */
        private final Object factory;

        /**
         * <p>
         * Builds a new registration instance.
         * </p>
         *
         * @param disposable disposable or not
         * @param ndbId ID for DAO resolution
         * @param heapIds the composition
         * @param paths the paths for this heap
         * @param listeners the listeners
         */
        private HeapRegistration(final boolean disposable,
                                 final String ndbId,
                                 final String[] heapIds,
                                 final String[] paths,
                                 final HeapListener ... listeners) {
            this.factory = currentTag;
            this.disposable = disposable;
            this.ndbId = ndbId;
            this.listeners = listeners;

            this.paths = new String[paths.length];
            System.arraycopy(paths, 0, this.paths, 0, paths.length);

            if (heapIds != null) {
                this.heapIds = new String[heapIds.length];
                System.arraycopy(heapIds, 0, this.heapIds, 0, heapIds.length);
            }
        }

        /**
         * <p>
         * Notifies the {@link NutsHeap} listeners if the object has been already created with a call to
         * {@link #getHeap(String, java.util.Map, java.util.Map, java.util.Map, ContextSetting)}.
         * This will help to free any resource.
         * </p>
         */
        void free() {
            if (heap != null) {
                heap.notifyListeners(heap);
                heap = null;
            }
        }

        /**
         * <p>
         * Indicates if the registration is for a composite heap.
         * </p>
         *
         * @return {@code true} if heaps a referenced, {@code false} otherwise
         */
        boolean isComposition() {
            return heapIds != null && heapIds.length > 0;
        }

        /**
         * <p>
         * Gets the {@link NutsHeap} created by this registration. The heap is created when the first call to this
         * method performed. Then, the instance will be created for future calls to take in consideration any change.
         * </p>
         *
         * @param id the ID for this heap
         * @param daoCollection a collection of DAOs where {@link #ndbId} will be resolved
         * @param heapCollection a collection of heap where {@link #heapIds} will be resolved
         * @param filterCollection the filter collection
         * @param contextSetting the setting this registration belongs to
         * @return the heap
         * @throws IOException if creation fails
         */
        NutsHeap getHeap(final String id,
                         final Map<String, NutDao> daoCollection,
                         final Map<String, NutsHeap> heapCollection,
                         final Map<String, NutFilter> filterCollection,
                         final ContextSetting contextSetting)
                throws IOException {
            free();
            NutDao dao = null;

            // Find DAO
            if (ndbId != null) {
                dao = daoCollection.get(ndbId);
            }

            // Check content and apply filters
            final List<String> pathList = pathList(dao, filterCollection.values(), ndbId, paths);

            // Composition detected, collected nested and referenced heaps
            if (heapIds != null && heapIds.length != 0) {
                final List<NutsHeap> composition = new ArrayList<NutsHeap>();

                for (final String regex : heapIds) {
                    for (final Map.Entry<String, HeapRegistration> registration : taggedSettings.getNutsHeap(regex).entrySet()) {
                        composition.add(heapCollection.get(registration.getKey()));
                    }
                }

                heap = new NutsHeap(factory, pathList, disposable, dao, id, composition.toArray(new NutsHeap[composition.size()]));
            } else {
                heap = new NutsHeap(factory, pathList, disposable, dao, id);
            }

            heap.checkFiles(contextSetting.getProcessContext());

            for (final HeapListener l : listeners) {
                heap.addObserver(l);
            }

            return heap;
        }
    }

    /**
     * <p>
     * A registration for a {@link com.github.wuic.WorkflowTemplate} to be created when the context is built.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 0.5.1
     */
    final class WorkflowTemplateRegistration {

        /**
         * The engine builder IDs to include in the template.
         */
        private final String[] ebIds;

        /**
         * The excluded IDs.
         */
        private final String[] ebIdsExclusion;

        /**
         * Include default engines or not.
         */
        private final Boolean includeDefaultEngines;

        /**
         * The DAO builder IDs to store nut.
         */
        private final String[] ndbIds;

        /**
         * <p>
         * Builds a new registration.
         * </p>
         *
         * @param ebIds the specific engines
         * @param ebIdsExclusion engines to exclude
         * @param includeDefaultEngines include default engines or not
         * @param ndbIds some DAO builder IDs to store nut
         */
        private WorkflowTemplateRegistration(final String[] ebIds,
                                             final String[] ebIdsExclusion,
                                             final Boolean includeDefaultEngines,
                                             final String[] ndbIds) {
            this.includeDefaultEngines = includeDefaultEngines;
            this.ebIds = new String[ebIds.length];
            System.arraycopy(ebIds, 0, this.ebIds, 0, ebIds.length);

            if (ebIdsExclusion != null) {
                this.ebIdsExclusion = new String[ebIdsExclusion.length];
                System.arraycopy(ebIdsExclusion, 0, this.ebIdsExclusion, 0, ebIdsExclusion.length);
            } else {
                this.ebIdsExclusion = null;
            }

            this.ndbIds = new String[ndbIds.length];
            System.arraycopy(ndbIds, 0, this.ndbIds, 0, ndbIds.length);
        }

        /**
         * <p>
         * Gets a new {@link com.github.wuic.WorkflowTemplate} for this registration.
         * </p>
         *
         * <p>
         * If the registered DAO does not exists don't support save operations
         * ({@link com.github.wuic.nut.dao.NutDao#saveSupported()}), then an {@link IllegalStateException}
         * is thrown.
         * </p>
         *
         * @param daoCollection the collection of DAO for ID resolution
         * @return the template
         */
        WorkflowTemplate getTemplate(final Map<String, NutDao> daoCollection) {
            final NutDao[] nutDaos = collect(daoCollection);

            // Retrieve each engine associated to all provided IDs and heap them by nut type
            final Map<NutType, NodeEngine> chains =
                    taggedSettings.createChains(configureDefault, engineBuilderFactory.knownTypes(), includeDefaultEngines, ebIdsExclusion);
            HeadEngine head = null;

            for (final String ebId : ebIds) {
                // Create a different instance per chain
                final Engine engine = taggedSettings.newEngine(ebId);

                if (engine == null) {
                    throw new IllegalStateException(String.format("'%s' not associated to any %s", ebId, EngineService.class.getName()));
                } else {

                    if (engine instanceof HeadEngine) {
                        head = HeadEngine.class.cast(engine);
                    } else {
                        final NodeEngine node = NodeEngine.class.cast(engine);
                        final List<NutType> nutTypes = node.getNutTypes();

                        for (final NutType nt : nutTypes) {
                            // Already exists
                            if (chains.containsKey(nt)) {
                                chains.put(nt, NodeEngine.chain(chains.get(nt), NodeEngine.class.cast(taggedSettings.newEngine(ebId))));
                            } else {
                                // Create first entry
                                chains.put(nt, node);
                            }
                        }
                    }
                }
            }

            return new WorkflowTemplate(head, chains, nutDaos);
        }

        /**
         * <p>
         * Collects in the given map the referenced DAOs.
         * </p>
         *
         * @param daoCollection all the instantiated DAOs
         * @return the collected DAOs
         */
        private NutDao[] collect(final Map<String, NutDao> daoCollection) {
            // Retrieve each DAO associated to all provided IDs
            final NutDao[] nutDaos = new NutDao[ndbIds.length];

            for (int i = 0; i < ndbIds.length; i++) {
                final String ndbId = ndbIds[i];
                final NutDao dao = daoCollection.get(ndbId);

                if (dao == null) {
                    throw new IllegalStateException(String.format("'%s' not associated to any %s", ndbId, NutDaoService.class.getName()));
                }

                if (!dao.saveSupported()) {
                    throw new IllegalStateException(String.format("DAO built by '%s' does not supports save", ndbId));
                }

                nutDaos[i] = dao;
            }

            return nutDaos;
        }
    }

    /**
     * <p>
     * A registration for a {@link com.github.wuic.Workflow} to be created when the context is built.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 0.5.1
     */
    final class WorkflowRegistration {

        /**
         * Create a workflow for each heap.
         */
        private final Boolean forEachHeap;

        /**
         * The heap pattern to resolve related heap.
         */
        private final String heapIdPattern;

        /**
         * A template ID.
         */
        private final String workflowTemplateId;

        /**
         * The factory the created heap.
         */
        private final Object factory;

        /**
         * <p>
         *  Builds a new registration
         * </p>
         *
         * @param forEachHeap for each heap or not
         * @param heapIdPattern heap resolver pattern
         * @param workflowTemplateId an optional workflow
         */
        private WorkflowRegistration(final Boolean forEachHeap,
                                     final String heapIdPattern,
                                     final String workflowTemplateId) {
            this.factory = currentTag;
            this.forEachHeap = forEachHeap;
            this.heapIdPattern = heapIdPattern;
            this.workflowTemplateId  = workflowTemplateId;
        }

        /**
         * <p>
         * Gets a new map of {@link com.github.wuic.Workflow} for this registration.
         * </p>
         *
         * @param identifier the workflow ID
         * @param daoCollection a collection of DAO for template creation
         * @param heapCollection a collection of heap for workflow creation
         * @param contextSetting the setting this registration belongs to
         * @return the new workflow
         * @throws WorkflowTemplateNotFoundException if the workflow template does not exists
         * @throws IOException if heap creation fails
         */
        Map<String, Workflow> getWorkflowMap(final String identifier,
                                             final Map<String, NutDao> daoCollection,
                                             final Map<String, NutsHeap> heapCollection,
                                             final ContextSetting contextSetting)
                throws WorkflowTemplateNotFoundException, IOException {
            final WorkflowTemplate template = taggedSettings.getWorkflowTemplate(workflowTemplateId, daoCollection);
            final Map<String, Workflow> retval = new HashMap<String, Workflow>();

            if (template == null) {
                WuicException.throwWorkflowTemplateNotFoundException(workflowTemplateId);
                return null;
            }

            final Map<NutType, ? extends NodeEngine> chains = template.getChains();
            final NutDao[] nutDaos = template.getStores();

            // Retrieve HEAP
            final Map<String, HeapRegistration> heaps = taggedSettings.getNutsHeap(heapIdPattern);

            if (heaps.isEmpty()) {
                throw new IllegalStateException(String.format("'%s' is a regex which doesn't match any %s", heapIdPattern, NutsHeap.class.getName()));
            }

            final String id = identifier.substring(0, identifier.length() - heapIdPattern.length());

            if (forEachHeap) {
                for (final Map.Entry<String, HeapRegistration> heap : heaps.entrySet()) {
                    final String loopId = id + heap.getKey();
                    if (NumberUtils.isNumber(loopId)) {
                        WuicException.throwBadArgumentException(new IllegalArgumentException(
                                String.format("Workflow ID %s cannot be a numeric value", loopId)));
                    }

                    retval.put(loopId, new Workflow(template.getHead(), chains, heapCollection.get(heap.getKey()), nutDaos));
                }
            } else {
                if (NumberUtils.isNumber(id)) {
                    WuicException.throwBadArgumentException(new IllegalArgumentException(
                            String.format("Workflow ID %s cannot be a numeric value", id)));
                }

                final NutsHeap[] array = new NutsHeap[heaps.size()];
                int cpt = 0;

                for (final Map.Entry<String, HeapRegistration> heap : heaps.entrySet()) {
                    array[cpt++] = heapCollection.get(heap.getKey());
                }

                final NutsHeap heap = new NutsHeap(factory, null, null, heapIdPattern, array);
                heap.checkFiles(contextSetting.getProcessContext());
                retval.put(id, new Workflow(template.getHead(), chains, heap));
            }

            return retval;
        }
    }

    /**
     * <p>
     * A registration for a {@link NutDao} to be created when the context is built.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 0.5.1
     */
    final class NutDaoRegistration implements ObjectBuilder<NutDao> {

        /**
         * The builder.
         */
        private final ObjectBuilder<NutDao> nutDaoBuilder;

        /**
         * Some DAO to proxy.
         */
        private final Map<String, String> proxyDao;

        /**
         * Some nut to proxy.
         */
        private final Map<String, Nut> proxyNut;

        /**
         * A root path for all proxy paths.
         */
        private String proxyRootPath;

        /**
         * The created DAO.
         */
        private NutDao dao;

        /**
         * <p>
         * Builds a new registration for the given builder.
         * </p>
         *
         * @param nutDaoBuilder the DAO builder
         */
        private NutDaoRegistration(final ObjectBuilder<NutDao> nutDaoBuilder) {
            this.proxyDao = new HashMap<String, String>();
            this.proxyNut = new HashMap<String, Nut>();
            this.nutDaoBuilder = nutDaoBuilder;
            this.proxyRootPath = "";
        }

        /**
         * <p>
         * Builds a new registration as a copy of the given registration.
         * </p>
         *
         * @param other the other registration to copy
         */
        private NutDaoRegistration(final NutDaoRegistration other) {
            this.proxyDao = other.proxyDao == null ? null : new HashMap<String, String>(other.proxyDao);
            this.proxyNut = other.proxyNut == null ? null : new HashMap<String, Nut>(other.proxyNut);
            this.nutDaoBuilder = other.nutDaoBuilder;
            this.proxyRootPath = other.proxyRootPath;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ObjectBuilder<NutDao> property(final String key, final Object value) {
            return nutDaoBuilder.property(key, value);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ObjectBuilder<NutDao> disableSupport(final String key, final Object value) {
            return nutDaoBuilder.disableSupport(key, value);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object property(final String key) {
            return nutDaoBuilder.property(key);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Map<String, Object> getProperties() {
            return nutDaoBuilder.getProperties();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public NutDao build() {
            return nutDaoBuilder.build();
        }

        /**
         * <p>
         * Gets the proxy for nuts.
         * </p>
         *
         * @return the proxy nut
         */
        private Map<String, Nut> getProxyNut() {
            return proxyNut;
        }

        /**
         * <p>
         * Gets the proxy for DAO. The key is the map, the value the {@link NutDao} registration ID.
         * </p>
         *
         * @return the DAO
         */
        private Map<String, String> getProxyDao() {
            return proxyDao;
        }

        /**
         * <p>
         * Sets the proxy root path.
         * </p>
         *
         * @param proxyRootPath the new root path
         */
        private void setProxyRootPath(final String proxyRootPath) {
            this.proxyRootPath = proxyRootPath;
        }

        /**
         * <p>
         * Configures the wrapped builder with the specified properties.
         * </p>
         *
         * @param properties the properties
         * @return this registration
         */
        private NutDaoRegistration configure(final Map<String, Object> properties) {
            ContextBuilder.this.configure(nutDaoBuilder, properties);
            return this;
        }

        /**
         * <p>
         * Shutdowns the {@link NutDao} if the object has been already created with a call to {@link #getNutDao(java.util.List)}.
         * </p>
         */
        void free() {
            if (dao != null) {
                dao.shutdown();
                dao = null;
            }
        }

        /**
         * <p>
         * Builds a new {@link NutDao} for this registration. The DAO is created when the first call to this method
         * performed. Then, a new instance will be created for future calls to take in consideration any change. For
         * instance, if the proxy settings have changed, the instance will be modified to provide an up to date state.
         * </p>
         *
         * @param populateProxy a list populated with created {@link ProxyNutDao} that declared rules for DAO to be set
         * @return the DAO
         */
        NutDao getNutDao(final List<ProxyNutDaoRegistration> populateProxy) {
            free();
            final NutDao delegate = nutDaoBuilder.build();

            // Must NutDao wrap in a proxy
            if (!proxyNut.isEmpty() || !proxyDao.isEmpty() || !proxyRootPath.isEmpty()) {
                final ProxyNutDao proxy = new ProxyNutDao(proxyRootPath, delegate);

                for (final Map.Entry<String, Nut> entry : proxyNut.entrySet()) {
                    proxy.addRule(entry.getKey(), entry.getValue());
                }

                for (final Map.Entry<String, String> entry : proxyDao.entrySet()) {
                    populateProxy.add(new ProxyNutDaoRegistration(entry.getKey(), entry.getValue(), proxy));
                }

                dao = proxy;
            } else {
                dao = delegate;
            }

            return dao;
        }
    }

    /**
     * <p>
     * Gets the setting associated to the current tag. If no tag is defined, then an {@link IllegalStateException} will
     * be thrown.
     * </p>
     *
     * @return the setting
     */
    ContextSetting getSetting() {
        if (currentTag == null) {
            throw new IllegalStateException("Call tag() method first");
        }

        ContextSetting setting = taggedSettings.get(currentTag);

        if (setting == null) {
            setting = new ContextSetting();
            taggedSettings.put(currentTag, setting);
        }

        return setting;
    }

    /**
     * <p>
     * Gets the default builder name for the given component class.
     * </p>
     *
     * @param component the component class
     * @return the default ID
     */
    public static String getDefaultBuilderId(final Class<?> component) {
        return getDefaultBuilderId(builderName(component.getSimpleName()));
    }

    /**
     * <p>
     * Gets the default builder ID for the given builder name.
     * </p>
     *
     * @param builderName the builder name
     * @return the default builder ID
     */
    public static String getDefaultBuilderId(final String builderName) {
        return "wuicDefault" + builderName;
    }

    /**
     * <p>
     * Gets the default builder ID.
     * </p>
     *
     * @return the default ID
     */
    public static String getDefaultBuilderId() {
        return getDefaultBuilderId(ClasspathNutDao.class);
    }

    /**
     * <p>
     * Configures for each type provided by the engine builder factory and nut dao builder factory a
     * default instance identified with an id starting by {@link #BUILDER_ID_PREFIX} and followed by the type
     * name itself.
     * </p>
     *
     * @return the current builder
     * @throws IOException if configuration fails
     */
    public ContextBuilder configureDefault() throws IOException {
        if (!configureDefault) {
            new DefaultContextBuilderConfigurator(engineBuilderFactory){
                @Override
                void internalConfigure(final ContextBuilder contextBuilder, final ObjectBuilderFactory.KnownType type) {
                    contextBuilder.contextEngineBuilder(BUILDER_ID_PREFIX + type.getTypeName(), type.getTypeName()).toContext();
                }
            }.configure(this);

            new DefaultContextBuilderConfigurator(nutDaoBuilderFactory) {
                @Override
                void internalConfigure(final ContextBuilder contextBuilder, final ObjectBuilderFactory.KnownType type) {
                    contextBuilder.contextNutDaoBuilder(BUILDER_ID_PREFIX + type.getTypeName(), type.getTypeName()).toContext();
                }
            }.configure(this);

            new DefaultContextBuilderConfigurator(nutFilterBuilderFactory) {
                @Override
                void internalConfigure(final ContextBuilder contextBuilder, final ObjectBuilderFactory.KnownType type) {
                    contextBuilder.contextNutFilterBuilder(BUILDER_ID_PREFIX + type.getTypeName(), type.getTypeName()).toContext();
                }
            }.configure(this);

            configureDefault = true;
        }

        return this;
    }

    /**
     * <p>
     * Builds a new {@link NutDao} builder.
     * </p>
     *
     * @param type the type of DAO
     * @return the builder
     */
    public synchronized ObjectBuilder<NutDao> newNutDaoBuilder(final String type) {
        return nutDaoBuilderFactory.create(type);
    }

    /**
     * <p>
     * Decorates the current builder with a new builder associated to a specified tag. Tagging the context allows to
     * isolate a set of configurations that could be erased by calling {@link ContextBuilder#clearTag(Object)}.
     * This way, this feature is convenient when you need to poll the configurations to reload it.
     * </p>
     *
     * <p>
     * All configurations will be associated to the tag until the {@link ContextBuilder#releaseTag()}
     * method is called. If tag is currently set, then it is released when this method is called with a new tag.
     * </p>
     *
     * @param tag an arbitrary object which represents the current tag
     * @return the current builder which will associates all configurations to the tag
     * @see ContextBuilder#clearTag(Object)
     * @see ContextBuilder#releaseTag()
     */
    public ContextBuilder tag(final Object tag) {
        lock.lock();
        log.debug("ContextBuilder locked by {}", Thread.currentThread().toString());

        if (currentTag != null) {
            releaseTag();
        }

        currentTag = tag;
        setChanged();
        notifyObservers(tag);
        return this;
    }

    /**
     * <p>
     * Sets the process context for the setting associated to the current tag and to any setting owning an non-null
     * process context that is an instance of the same given object class.
     * </p>
     *
     * @param processContext the {@link ProcessContext}
     * @return this builder
     */
    public ContextBuilder processContext(final ProcessContext processContext) {
        taggedSettings.setProcessContext(processContext);
        getSetting().setProcessContext(processContext);
        setChanged();
        notifyObservers();
        return this;
    }

    /**
     * <p>
     * Clears all configurations associated to the given tag.
     * </p>
     *
     * @param tag the tag
     * @return this {@link ContextBuilder}
     */
    public ContextBuilder clearTag(final Object tag) {
        try {
            if (!lock.isHeldByCurrentThread()) {
                lock.lock();
            }

            final ContextSetting setting = taggedSettings.remove(tag);

            // Shutdown all DAO (scheduled jobs, etc)
            if (setting != null) {
                for (final NutDaoRegistration dao : setting.getNutDaoMap().values()) {
                    dao.free();
                }

                // Notifies any listeners to clear any cache
                for (final HeapRegistration heap : setting.getNutsHeaps().values()) {
                    heap.free();
                }
            }

            setChanged();
            notifyObservers(tag);

            return this;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>
     * Releases the current tag of this context. When the configurations associated to a tag are finished, it could be
     * released by calling this method to not tag next configurations.
     * </p>
     *
     * @return this current builder without tag
     */
    public ContextBuilder releaseTag() {
        try {
            // Won't block if the thread already own the
            if (!lock.isHeldByCurrentThread()) {
                lock.lock();
                log.debug("ContextBuilder locked by {}", Thread.currentThread().toString());
            }

            // Check that a tag exists
            getSetting();
            currentTag = null;
            setChanged();
            notifyObservers();

            return this;
        } finally {
            // Release the lock
            lock.unlock();
            log.debug("ContextBuilder unlocked by {}", Thread.currentThread().toString());
        }
    }

    /**
     * <p>
     * Inner class to configure a generic component.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 0.4.4
     */
    public abstract class ContextGenericBuilder {

        /**
         * The ID.
         */
        private String id;

        /**
         * The properties.
         */
        private Map<String, Object> properties;

        /**
         * <p>
         * Builds a new component identified by the given ID.
         * </p>
         *
         * @param id the ID
         */
        public ContextGenericBuilder(final String id) {
            this.id = id;
            this.properties = new HashMap<String, Object>();
        }

        /**
         * <p>
         * Gets the ID.
         * </p>
         *
         * @return the ID
         */
        protected String getId() {
            return id;
        }

        /**
         * <p>
         * Gets the properties
         * </p>
         *
         * @return the properties
         */
        protected Map<String, Object> getProperties() {
            return properties;
        }

        /***
         * <p>
         * Configures the given property.
         * </p>
         *
         * @param key the property key
         * @param value the property value
         * @return this
         */
        public abstract ContextGenericBuilder property(String key, Object value);

        /**
         * <p>
         * Injects in the enclosing builder the component with its settings and return it.
         * </p>
         *
         * <p>
         * Throws an {@link IllegalArgumentException} if a previously configured property is not supported
         * </p>
         *
         * @return the enclosing builder
         */
        public abstract ContextBuilder toContext();
    }

    /**
     * <p>
     * Inner class to configure a engine builder.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 0.4.4
     */
    public class ContextEngineBuilder extends ContextGenericBuilder {

        /**
         * The builder.
         */
        private ObjectBuilder<Engine> engineBuilder;

        /**
         * <p>
         * Builds a new instance.
         * </p>
         *
         * @param id the builder ID
         * @param type the builder type
         */
        public ContextEngineBuilder(final String id, final String type) {
            super(id);
            this.engineBuilder = engineBuilderFactory.create(type);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ContextEngineBuilder property(final String key, final Object value) {
            getProperties().put(key, value);
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ContextBuilder toContext() {
            engineBuilder(getId(), engineBuilder, getProperties());
            return ContextBuilder.this;
        }
    }

    /**
     * <p>
     * Inner class to configure a DAO builder.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 0.4.4
     */
    public class ContextNutDaoBuilder extends ContextGenericBuilder {

        /**
         * The wrapped registration.
         */
        private final NutDaoRegistration registration;

        /**
         * <p>
         * Builds a new instance.
         * </p>
         *
         * @param id the builder ID
         * @param type the builder type
         */
        public ContextNutDaoBuilder(final String id, final String type) {
            super(id);
            this.registration = new NutDaoRegistration(nutDaoBuilderFactory.create(type));
        }

        /**
         * <p>
         * Builds a new instance with an existing registration.
         * </p>
         *
         * @param id the builder ID
         * @param registration the registration
         */
        public ContextNutDaoBuilder(final String id, final NutDaoRegistration registration) {
            super(id);
            this.registration = registration;
        }

        /**
         * <p>
         * Specifies a root path for paths to proxy.
         * </p>
         *
         * @param path the root path
         * @return this
         */
        public ContextNutDaoBuilder proxyRootPath(final String path) {
            registration.setProxyRootPath(path);
            return this;
        }

        /**
         * <p>
         * Specifies a path to proxy to a DAO.
         * </p>
         *
         * @param path the path
         * @param id the DAO id
         * @return this
         */
        public ContextNutDaoBuilder proxyPathForDao(final String path, final String id) {
            registration.getProxyDao().put(path, id);
            return this;
        }

        /**
         * <p>
         * Specifies a path to proxy to a nut.
         * </p>
         *
         * @param path the path
         * @param nut the nut
         * @return this
         */
        public ContextNutDaoBuilder proxyPathForNut(final String path, final Nut nut) {
            registration.getProxyNut().put(path, nut);
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ContextNutDaoBuilder property(final String key, final Object value) {
            getProperties().put(key, value);
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ContextBuilder toContext() {
            nutDaoBuilder(getId(), registration, getProperties());
            return ContextBuilder.this;
        }
    }

    /**
     * <p>
     * Inner class to configure a filter builder.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 0.4.5
     */
    public class ContextNutFilterBuilder extends ContextGenericBuilder {

        /**
         * The builder.
         */
        private ObjectBuilder<NutFilter> nutFilterBuilder;

        /**
         * <p>
         * Builds a new instance.
         * </p>
         *
         * @param id the builder ID
         * @param type the builder type
         */
        public ContextNutFilterBuilder(final String id, final String type) {
            super(id == null ? getDefaultBuilderId(type) : id);
            this.nutFilterBuilder = nutFilterBuilderFactory.create(type);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ContextNutFilterBuilder property(final String key, final Object value) {
            getProperties().put(key, value);
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ContextBuilder toContext() {
            nutFilterBuilder(getId(), nutFilterBuilder, getProperties());
            return ContextBuilder.this;
        }
    }

    /**
     * <p>
     * Returns a new context DAO builder.
     * </p>
     *
     * @param userId the final builder's ID, default ID if {@code null}
     * @param type the final builder's type
     * @return the specific context builder
     */
    public ContextNutDaoBuilder contextNutDaoBuilder(final String userId, final String type) {
        final String id = userId == null ? getDefaultBuilderId(type) : userId;
        final NutDaoRegistration registration = taggedSettings.getNutDaoRegistration(id);
        return registration == null ? new ContextNutDaoBuilder(id, type) : new ContextNutDaoBuilder(id, registration);
    }

    /**
     * <p>
     * Returns a new default context DAO builder.
     * </p>
     *
     * @param type the component to build
     * @return the specific context builder
     */
    public ContextNutDaoBuilder contextNutDaoBuilder(final Class<?> type) {
        return contextNutDaoBuilder(getDefaultBuilderId(type), type);
    }

    /**
     * <p>
     * Returns a new default context DAO builder associated to a particular ID.
     * </p>
     *
     * @param id the specific ID
     * @param type the component to build
     * @return the specific context builder
     */
    public ContextNutDaoBuilder contextNutDaoBuilder(final String id, final Class<?> type) {
        return contextNutDaoBuilder(id, builderName(type.getSimpleName()));
    }

    /**
     * <p>
     * Returns a new context DAO builder associated to a particular ID and based on an existing context DAO builder.
     * </p>
     *
     * @param id the specific ID
     * @param userId the ID of the existing builder to clone, if {@code null} then the default DAO is used
     * @return the specific context builder
     */
    public ContextNutDaoBuilder cloneContextNutDaoBuilder(final String id, final String userId) {
        final String cloneId = userId == null ? getDefaultBuilderId() : userId;
        final NutDaoRegistration registration = taggedSettings.getNutDaoRegistration(cloneId);

        if (registration == null) {
            WuicException.throwBadArgumentException(new IllegalArgumentException(
                    String.format("%s must be an existing NutDao builder to be cloned", cloneId)));
        }

        return new ContextNutDaoBuilder(id, new NutDaoRegistration(registration));
    }

    /**
     * <p>
     * Returns a new context filter builder.
     * </p>
     *
     * @param id the final builder's ID
     * @param type the final builder's type
     * @return the specific context builder
     */
    public ContextNutFilterBuilder contextNutFilterBuilder(final String id, final String type) {
        return new ContextNutFilterBuilder(id, type);
    }

    /**
     * <p>
     * Returns a new context filter builder.
     * </p>
     *
     * @param id the final builder's ID
     * @param type the final builder's type
     * @return the specific context builder
     */
    public ContextNutFilterBuilder contextNutFilterBuilder(final String id, final Class<? extends NutFilter> type) {
        return contextNutFilterBuilder(id, builderName(type.getSimpleName()));
    }

    /**
     * <p>
     * Returns a new context engine builder.
     * </p>
     *
     * @param id the final builder's ID
     * @param type the final builder's type
     * @return the specific context builder
     */
    public ContextEngineBuilder contextEngineBuilder(final String id, final String type) {
        return new ContextEngineBuilder(id == null ? getDefaultBuilderId(type) : id, type);
    }

    /**
     * <p>
     * Returns a new context default engine builder.
     * </p>
     *
     * @param type the component to build
     * @return the specific context builder
     */
    public ContextEngineBuilder contextEngineBuilder(final Class<?> type) {
        return new ContextEngineBuilder(getDefaultBuilderId(type), builderName(type.getSimpleName()));
    }

    /**
     * <p>
     * Returns a new default context engine builder associated to a particular ID.
     * </p>
     *
     * @param id the specific ID, if {@code null} then the default ID is used
     * @param type the component to build
     * @return the specific context builder
     */
    public ContextEngineBuilder contextEngineBuilder(final String id, final Class<?> type) {
        return contextEngineBuilder(id == null ? getDefaultBuilderId(type) : id, builderName(type.getSimpleName()));
    }

    /**
     * <p>
     * Adds a {@link ContextInterceptor} to the builder.
     * </p>
     *
     * @param interceptor the interceptor
     * @return this {@link ContextBuilder}
     */
    public ContextBuilder interceptor(final ContextInterceptor interceptor) {
        final ContextSetting setting = getSetting();
        setting.getInterceptorsList().add(interceptor);
        taggedSettings.put(currentTag, setting);
        setChanged();
        notifyObservers();
        return this;
    }

    /**
     * <p>
     * Add a new {@link NutDaoRegistration} identified by the specified ID.
     * </p>
     *
     * @param id the ID which identifies the builder in the context
     * @param registration the registration associated to its ID
     * @return this {@link ContextBuilder}
     */
    ContextBuilder nutDao(final String id, final NutDaoRegistration registration) {
        final ContextSetting setting = getSetting();

        // Will override existing element
        taggedSettings.removeNutDaoRegistration(id);

        setting.getNutDaoMap().put(id, registration);
        taggedSettings.put(currentTag, setting);
        setChanged();
        notifyObservers(id);

        return this;
    }

    /**
     * <p>
     * Add a new {@link com.github.wuic.nut.filter.NutFilter} builder identified by the specified ID.
     * </p>
     *
     * @param id the ID which identifies the builder in the context
     * @param filter the filter builder associated to its ID
     * @return this {@link ContextBuilder}
     */
    public ContextBuilder nutFilter(final String id, final ObjectBuilder<NutFilter> filter) {
        final ContextSetting setting = getSetting();

        // Will override existing element
        taggedSettings.removeNutFilter(id);

        setting.getNutFilterMap().put(id, filter);
        taggedSettings.put(currentTag, setting);
        setChanged();
        notifyObservers(id);

        return this;
    }

    /**
     * <p>
     * Add a new {@link com.github.wuic.engine.Engine} builder identified by the specified ID.
     * </p>
     *
     * @param id the ID which identifies the builder in the context
     * @param engine the engine builder associated to its ID
     * @return this {@link ContextBuilder}
     */
    public ContextBuilder engineBuilder(final String id, final ObjectBuilder<Engine> engine) {
        final ContextSetting setting = getSetting();

        // Will override existing element
        taggedSettings.removeEngine(id);

        setting.getEngineMap().put(id, engine);
        taggedSettings.put(currentTag, setting);
        setChanged();
        notifyObservers(id);

        return this;
    }

    /**
     * <p>
     * Adds a new {@link com.github.wuic.nut.dao.NutDao} builder identified by the specified ID.
     * </p>
     *
     * <p>
     * If some properties are not supported by the builder, then an exception will be thrown.
     * </p>
     *
     * <p>
     * Throws an {@link IllegalArgumentException} if a property is not supported by the builder.
     * </p>
     *
     * @param id the ID which identifies the builder in the context
     * @param daoRegistration the registration wrapping the builder associated to its ID
     * @param properties the properties to use to configure the builder
     * @return this {@link ContextBuilder}
     */
    private ContextBuilder nutDaoBuilder(final String id,
                                         final NutDaoRegistration daoRegistration,
                                         final Map<String, Object> properties) {
        final ContextSetting setting = getSetting();

        // Will override existing element
        taggedSettings.removeNutDaoRegistration(id);

        setting.getNutDaoMap().put(id, daoRegistration.configure(properties));
        taggedSettings.put(currentTag, setting);
        setChanged();
        notifyObservers(id);

        return this;
    }

    /**
     * <p>
     * Builds the builder's name based on the given type.
     * </p>
     *
     * @param type the component type
     * @return the component builder name
     */
    private static String builderName(final String type) {
        return type + "Builder";
    }

    /**
     * <p>
     * Adds a new {@link com.github.wuic.nut.filter.NutFilter} builder identified by the specified ID.
     * </p>
     *
     * <p>
     * If some properties are not supported by the builder, then an exception will be thrown.
     * </p>
     *
     * @param id the ID which identifies the builder in the context
     * @param filterBuilder the builder associated to its ID
     * @param properties the properties to use to configure the builder
     * @return this {@link ContextBuilder}
     */
    private ContextBuilder nutFilterBuilder(final String id,
                                            final ObjectBuilder<NutFilter> filterBuilder,
                                            final Map<String, Object> properties) {
        final ContextSetting setting = getSetting();

        // Will override existing element
        taggedSettings.removeNutFilter(id);

        setting.getNutFilterMap().put(id, configure(filterBuilder, properties));
        taggedSettings.put(currentTag, setting);
        setChanged();
        notifyObservers(id);

        return this;
    }

    /**
     * <p>
     * Creates a new heap as specified by {@link ContextBuilder#heap(boolean, String, String, String[], String[], HeapListener...)}
     * without any composition and which is not disposable.
     * </p>
     *
     * @param id the heap ID
     * @param ndbId the {@link com.github.wuic.nut.dao.NutDao} builder the heap is based on
     * @param path the path
     * @param listeners some listeners for this heap
     * @return this {@link ContextBuilder}
     * @throws IOException if the HEAP could not be created
     */
    public ContextBuilder heap(final String id, final String ndbId, final String[] path, final HeapListener ... listeners)
            throws IOException {
        return heap(false, id, ndbId, null, path, listeners);
    }

    /**
     * <p>
     * Creates a new disposable heap as specified by {@link ContextBuilder#heap(boolean, String, String, String[], String[], HeapListener...)}
     * without any composition.
     * </p>
     *
     * @param id the heap ID
     * @param ndbId the {@link com.github.wuic.nut.dao.NutDao} builder the heap is based on
     * @param path the path
     * @param listeners some listeners for this heap
     * @return this {@link ContextBuilder}
     * @throws IOException if the HEAP could not be created
     */
    public ContextBuilder disposableHeap(final String id, final String ndbId, final String[] path, final HeapListener ... listeners)
            throws IOException {
        return heap(true, id, ndbId, null, path, listeners);
    }

    /**
     * <p>
     * Defines a new {@link com.github.wuic.nut.NutsHeap heap} in this context. A heap is always identified
     * by an ID and is associated to {@link com.github.wuic.nut.dao.NutDao} builder to use to convert paths into
     * {@link com.github.wuic.nut.Nut}. A list of paths needs also to be specified to know which underlying
     * nuts compose the heap.
     * </p>
     *
     * <p>
     * The heap could be composed in part or totally of other heaps.
     * </p>
     *
     * <p>
     * If the {@link com.github.wuic.config.ObjectBuilder} ID is not known, a {@link IllegalArgumentException}
     * will be thrown.
     * </p>
     *
     * @param disposable if the heap is disposable or not (see {@link com.github.wuic.nut.dao.NutDaoListener#isDisposable()}
     * @param id the heap ID
     * @param heapIds the heaps composition
     * @param nutDaoId the {@link com.github.wuic.nut.dao.NutDao} builder the heap is based on, default is used if {@code null}
     * @param path the path
     * @param listeners some listeners for this heap
     * @return this {@link ContextBuilder}
     * @throws IOException if the HEAP could not be created
     */
    public ContextBuilder heap(final boolean disposable,
                               final String id,
                               final String nutDaoId,
                               final String[] heapIds,
                               final String[] path,
                               final HeapListener ... listeners)
            throws IOException {
        if (NumberUtils.isNumber(id)) {
            WuicException.throwBadArgumentException(new IllegalArgumentException(String.format("Heap ID %s cannot be a numeric value", id)));
        }

        // Will override existing element
        taggedSettings.removeHeapRegistration(id);

        final ContextSetting setting = getSetting();

        final String ndbId = nutDaoId != null ? nutDaoId : getDefaultBuilderId();
        setting.getNutsHeaps().put(id, new HeapRegistration(disposable, ndbId, heapIds, path, listeners));

        taggedSettings.put(currentTag, setting);
        setChanged();
        notifyObservers(id);

        return this;
    }

    /**
     * <p>
     * This internal methods takes the declared paths specified in parameters, filters them and returns teh result.
     * </p>
     *
     * <p>
     * Also throws a {@link IllegalArgumentException} if their is at least one path while the specified DAO is {@code null}.
     * </p>
     *
     * @param dao the {@link NutDao} that creates {@link com.github.wuic.nut.Nut} with given path
     * @param filters the filter collection
     * @param ndbId the ID associated to the DAO
     * @param path the paths that represent the {@link com.github.wuic.nut.Nut nuts}
     * @return the filtered paths
     */
    private List<String> pathList(final NutDao dao, final Collection<NutFilter> filters, final String ndbId, final String ... path) {
        List<String> pathList;

        if (path.length != 0) {
            if (dao == null) {
                final String msg = String.format("'%s' does not correspond to any %s, add it with nutDaoBuilder() first",
                        ndbId, NutDaoService.class.getName());
                WuicException.throwBadArgumentException(new IllegalArgumentException(msg));
                return null;
            } else {
                pathList = CollectionUtils.newList(path);

                // Going to filter the list with all declared filters
                for (final NutFilter filter : filters) {
                    pathList = filter.filterPaths(pathList);
                }
            }
        } else {
            pathList = Arrays.asList();
        }

        return pathList;
    }

    /**
     * <p>
     * Declares a new {@link com.github.wuic.engine.Engine} builder with its specific properties.
     * The builder is identified by an unique ID and produces in fine {@link com.github.wuic.engine.Engine engines}
     * that could be chained.
     * </p>
     *
     * @param id the {@link com.github.wuic.config.ObjectBuilder} ID
     * @param engineBuilder the {@link com.github.wuic.engine.Engine} builder to configure
     * @param properties the builder's properties (must be supported by the builder)
     * @return this {@link ContextBuilder}
     */
    private ContextBuilder engineBuilder(final String id,
                                         final ObjectBuilder<Engine> engineBuilder,
                                         final Map<String, Object> properties) {
        final ContextSetting setting = getSetting();

        // Will override existing element
        taggedSettings.removeEngine(id);

        setting.getEngineMap().put(id, configure(engineBuilder, properties));
        taggedSettings.put(currentTag, setting);
        setChanged();
        notifyObservers(id);

        return this;
    }

    /**
     * <p>
     * Builds a new template with no exclusion and default engine usages.
     * </p>
     *
     * @param id the template's id
     * @param ebIds the set of {@link com.github.wuic.engine.Engine} builder to use
     * @param daos the DAO
     * @return this {@link ContextBuilder}
     * @throws IOException if an I/O error occurs
     * @see ContextBuilder#template(String, String[], String[], Boolean, String...)
     */
    public ContextBuilder template(final String id,
                                   final String[] ebIds,
                                   final String ... daos) throws IOException {
        return template(id, ebIds, null, Boolean.TRUE, daos);
    }

    /**
     * <p>
     * Creates a new workflow template.
     * </p>
     *
     * <p>
     * The template consists to chain a set of engines produced by the specified {@link com.github.wuic.engine.Engine} builders.
     * There is a chain for each possible {@link NutType}. A chain that processes a particular {@link NutType} of
     * {@link com.github.wuic.nut.Nut} is composed of {@link Engine engines} ordered by type. All engines specified in
     * parameter as array are simply organized following those two criteria to create the chains. Moreover, default engines
     * could be injected in the chain to perform common operations to be done on nuts. If an {@link com.github.wuic.engine.Engine} builder
     * is specified in a chain while it is injected by default, then the configuration of the given builder will overrides
     * the default one.
     * </p>
     *
     * <p>
     * A set of {@link com.github.wuic.nut.dao.NutDao} builder could be specified to store processed nuts. When the client
     * will retrieve the nuts, it will access it through a proxy URI configured in the protocol. This URI corresponds
     * to a server in front of the location where nuts have been stored. For that reason the {@link NutDao} must
     * support {@link NutDao#save(com.github.wuic.nut.Nut)} operation.
     * </p>
     *
     * <p>
     * If the context builder should include engines by default, then a set of default engine to be excluded could be specified.
     * </p>
     *
     * <p>
     * An {@link IllegalStateException} will be thrown if the context is not correctly configured. Bad settings are :
     *  <ul>
     *      <li>Unknown {@link com.github.wuic.config.ObjectBuilder} ID</li>
     *      <li>A {@link NutDao} does not supports {@link NutDao#save(com.github.wuic.nut.Nut)} method</li>
     *  </ul>
     * </p>
     *
     * @param id the template's id
     * @param ebIds the set of {@link com.github.wuic.engine.Engine} builder to use
     * @param ebIdsExclusion some default builder to be excluded in the chain
     * @param ndbIds the set of {@link com.github.wuic.nut.dao.NutDao} builder where to eventually upload processed nuts
     * @param includeDefaultEngines include or not default engines
     * @return this {@link ContextBuilder}
     * @throws IOException if an I/O error occurs
     */
    public ContextBuilder template(final String id,
                                   final String[] ebIds,
                                   final String[] ebIdsExclusion,
                                   final Boolean includeDefaultEngines,
                                   final String ... ndbIds) throws IOException {
        final ContextSetting setting = getSetting();
        setting.getTemplateMap().put(id, new WorkflowTemplateRegistration(ebIds, ebIdsExclusion, includeDefaultEngines, ndbIds));
        taggedSettings.put(currentTag, setting);
        setChanged();
        notifyObservers(id);

        return this;
    }

    /**
     * <p>
     * Creates a new workflow. Any nut processing will be done through an existing workflow.
     * </p>
     *
     * <p>
     * A workflow is based on a {@link WorkflowTemplate} with a specified ID.
     * </p>
     *
     * <p>
     * The {@link NutsHeap heap} to be used is represented by a regex. The forEachHeap parameter indicates if only one
     * workflow should be created having as {@link NutsHeap heap} a composition of all heaps matching the pattern. If the
     * parameter is {@code false}, then a workflow is created for each matching {@link NutsHeap}. In this case, the workflow
     * ID will be the concatenation if the given identifier and the heap's ID.
     * </p>
     *
     * <p>
     * An {@link IllegalStateException} will be thrown if the context is not correctly configured. Bad settings are :
     *  <ul>
     *      <li>Unknown {@link NutsHeap} ID</li>
     *  </ul>
     * </p>
     *
     * @param identifier the identifier used to build the workflow ID, is the prefix if create one for each heap
     * @param forEachHeap {@code true} if a dedicated workflow must be created for each matching heap, {@code false} for a composition
     * @param heapIdPattern the regex matching the heap IDs that needs to be processed
     * @param workflowTemplateId a template ID to initialize the workflow
     * @return this {@link ContextBuilder}
     * @throws IOException if an I/O error occurs
     * @throws WorkflowTemplateNotFoundException if the specified template ID does not exists
     */
    public ContextBuilder workflow(final String identifier,
                                   final Boolean forEachHeap,
                                   final String heapIdPattern,
                                   final String workflowTemplateId)
            throws IOException, WorkflowTemplateNotFoundException {
        final ContextSetting setting = getSetting();
        final String id = identifier + heapIdPattern;

        // Will override existing element
        taggedSettings.removeWorkflowRegistration(id);

        setting.getWorkflowMap().put(id, new WorkflowRegistration(forEachHeap, heapIdPattern, workflowTemplateId));
        taggedSettings.put(currentTag, setting);
        setChanged();
        notifyObservers(identifier);

        return this;
    }

    /**
     * <p>
     * Gets the {@link NutFilter filters} currently configured in this builder.
     * </p>
     *
     * @return the filters
     */
    public List<NutFilter> getFilters() {
        final List<NutFilter> retval = new ArrayList<NutFilter>();
        retval.addAll(taggedSettings.getFilterMap().values());
        return retval;
    }

    /**
     * <p>
     * Merges all the {@link ContextSetting settings} of the given {@link ContextBuilder} to the current setting of
     * this object.
     * </p>
     *
     * @param other the object to merge
     * @return this
     */
    public ContextBuilder mergeSettings(final ContextBuilder other) {
        taggedSettings.mergeSettings(this, other.taggedSettings, currentTag);
        setChanged();
        notifyObservers(getSetting());
        return this;
    }

    /**
     * <p>
     * Applies all properties in the given parameter that are supported by any component configured in this builder.
     * </p>
     *
     * @param properties the properties object
     */
    public void applyProperties(final Properties properties) {
        taggedSettings.applyProperties(properties);
    }

    /**
     * <p>
     * Builds the context. Should throws an {@link IllegalStateException} if the context is not correctly configured.
     * For instance: associate a heap to an undeclared {@link com.github.wuic.nut.dao.NutDao} builder ID.
     * </p>
     *
     * @return the new {@link Context}
     * @throws WuicException if context construction fails
     */
    public Context build() throws WuicException {

        // This thread needs to lock to build the context
        // However, if it is already done, the method should not unlock at the end of the method
        final boolean requiresLock = !lock.isHeldByCurrentThread();

        try {
            if (requiresLock) {
                lock.lock();
            }

            if (propertySet != null) {
                InputStream is = null;

                try {
                    is = propertySet.openStream();
                    final Properties properties = new Properties();
                    properties.load(is);
                    applyProperties(properties);
                } finally {
                    IOUtils.close(is);
                }
            }

            final Map<String, NutFilter> filterMap = taggedSettings.getFilterMap();
            final Map<String, NutDao> daoMap = taggedSettings.getNutDaoMap();
            final Map<String, NutsHeap> heapMap = taggedSettings.getNutsHeapMap(daoMap, filterMap);
            final Map<String, Workflow> workflowMap =
                    taggedSettings.getWorkflowMap(configureDefault, daoMap, heapMap, engineBuilderFactory.knownTypes());

            return new Context(this, workflowMap, taggedSettings.getInspectors());
        } catch (IOException ioe) {
            WuicException.throwWuicException(ioe);
            return null;
        } finally {
            if (requiresLock) {
                lock.unlock();
            }
        }
    }

    /**
     * <p>
     * Gets the {@link ObjectBuilderFactory} which builds {@link Engine}.
     * </p>
     *
     * @return the factory
     */
    ObjectBuilderFactory<Engine> getEngineBuilderFactory() {
        return engineBuilderFactory;
    }

    /**
     * <p>
     * Gets the {@link ObjectBuilderFactory} which builds {@link NutDao}.
     * </p>
     *
     * @return the factory
     */
    ObjectBuilderFactory<NutDao> getNutDaoBuilderFactory() {
        return nutDaoBuilderFactory;
    }

    /**
     * <p>
     * Gets the {@link ObjectBuilderFactory} which builds {@link NutFilter}.
     * </p>
     *
     * @return the factory
     */
    ObjectBuilderFactory<NutFilter> getNutFilterBuilderFactory() {
        return nutFilterBuilderFactory;
    }

    /**
     * <p>
     * Configures the given builder with the specified properties and then return it.
     * </p>
     *
     * <p>
     * Throws an {@link IllegalArgumentException} if a specified property is not supported by the builder
     * </p>
     *
     * @param builder the builder
     * @param properties the properties to use to configure the builder
     * @param <O> the type produced by the builder
     * @param <T> the type of builder
     * @return the given builder
     */
    private <O, T extends ObjectBuilder<O>> T configure(final T builder,  final Map<String, Object> properties) {
        for (final Map.Entry entry : properties.entrySet()) {
            builder.property(String.valueOf(entry.getKey()), entry.getValue());
        }

        return builder;
    }
}