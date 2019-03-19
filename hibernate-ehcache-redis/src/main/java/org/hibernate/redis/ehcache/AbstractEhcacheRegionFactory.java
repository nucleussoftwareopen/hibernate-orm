package org.hibernate.redis.ehcache;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;

import org.hibernate.redis.ehcache.regions.EhcacheCollectionRegion;
import org.hibernate.redis.ehcache.regions.EhcacheEntityRegion;
import org.hibernate.redis.ehcache.regions.EhcacheNaturalIdRegion;
import org.hibernate.redis.ehcache.regions.EhcacheQueryResultsRegion;
import org.hibernate.redis.ehcache.regions.EhcacheTimestampsRegion;
import org.hibernate.redis.ehcache.strategy.EhcacheAccessStrategyFactory;
import org.hibernate.redis.ehcache.strategy.EhcacheAccessStrategyFactoryImpl;
import org.hibernate.redis.ehcache.strategy.NonstopAccessStrategyFactory;
import org.hibernate.boot.registry.classloading.spi.ClassLoaderService;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cache.CacheException;
import org.hibernate.cache.ehcache.EhCacheMessageLogger;
import org.hibernate.cache.spi.CacheDataDescription;
import org.hibernate.cache.spi.CollectionRegion;
import org.hibernate.cache.spi.EntityRegion;
import org.hibernate.cache.spi.NaturalIdRegion;
import org.hibernate.cache.spi.QueryResultsRegion;
import org.hibernate.cache.spi.RegionFactory;
import org.hibernate.cache.spi.TimestampsRegion;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.service.spi.InjectService;
import org.jboss.logging.Logger;
import org.redisson.api.BatchOptions;
import org.redisson.api.LocalCachedMapOptions;
import org.redisson.api.RedissonClient;

import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.hibernate.management.impl.ProviderMBeanRegistrationHelper;

abstract class AbstractEhcacheRegionFactory implements RegionFactory {

	private static final long serialVersionUID = 1L;

	/**
	 * The Hibernate system property specifying the location of the ehcache
	 * configuration file name.
	 * <p/>
	 * If not set, ehcache.xml will be looked for in the root of the classpath.
	 * <p/>
	 * If set to say ehcache-1.xml, ehcache-1.xml will be looked for in the root of
	 * the classpath.
	 */
	public static final String NET_SF_EHCACHE_CONFIGURATION_RESOURCE_NAME = "net.sf.ehcache.configurationResourceName";

	private static final EhCacheMessageLogger LOG = Logger.getMessageLogger(EhCacheMessageLogger.class,
			AbstractEhcacheRegionFactory.class.getName());

	/**
	 * MBean registration helper class instance for Ehcache Hibernate MBeans.
	 */
	protected final ProviderMBeanRegistrationHelper mbeanRegistrationHelper = new ProviderMBeanRegistrationHelper();

	/**
	 * Ehcache CacheManager that supplied Ehcache instances for this Hibernate
	 * RegionFactory.
	 */
	protected volatile CacheManager manager;

	/**
	 * Settings object for the Hibernate persistence unit.
	 */
	protected SessionFactoryOptions settings;

	/**
	 * {@link EhcacheAccessStrategyFactory} for creating various access strategies
	 */
	protected final EhcacheAccessStrategyFactory accessStrategyFactory = new NonstopAccessStrategyFactory(
			new EhcacheAccessStrategyFactoryImpl());

	protected RedissonClient redisson;
	protected BatchOptions batchOptions;
	protected LocalCachedMapOptions<Object, Object> localCachedMapOptions;

	/**
	 * {@inheritDoc}
	 * <p/>
	 * In Ehcache we default to minimal puts since this should have minimal to no
	 * affect on unclustered users, and has great benefit for clustered users.
	 *
	 * @return true, optimize for minimal puts
	 */
	@Override
	public boolean isMinimalPutsEnabledByDefault() {

		return true;
	}

	@Override
	public long nextTimestamp() {
		return net.sf.ehcache.util.Timestamper.next();
	}

	@Override
	public EntityRegion buildEntityRegion(String regionName, Properties properties, CacheDataDescription metadata)
			throws CacheException {
		return new EhcacheEntityRegion(accessStrategyFactory, getCache(regionName), settings, metadata, properties,
				redisson, batchOptions, localCachedMapOptions);
	}

	@Override
	public NaturalIdRegion buildNaturalIdRegion(String regionName, Properties properties, CacheDataDescription metadata)
			throws CacheException {
		return new EhcacheNaturalIdRegion(accessStrategyFactory, getCache(regionName), settings, metadata, properties,
				redisson, batchOptions, localCachedMapOptions);
	}

	@Override
	public CollectionRegion buildCollectionRegion(String regionName, Properties properties,
			CacheDataDescription metadata) throws CacheException {
		return new EhcacheCollectionRegion(accessStrategyFactory, getCache(regionName), settings, metadata, properties,
				redisson, batchOptions, localCachedMapOptions);
	}

	@Override
	public QueryResultsRegion buildQueryResultsRegion(String regionName, Properties properties) throws CacheException {
		return new EhcacheQueryResultsRegion(accessStrategyFactory, getCache(regionName), properties, redisson,
				batchOptions, localCachedMapOptions);
	}

	@InjectService
	public void setClassLoaderService(ClassLoaderService classLoaderService) {
		this.classLoaderService = classLoaderService;
	}

	private ClassLoaderService classLoaderService;

	@Override
	public TimestampsRegion buildTimestampsRegion(String regionName, Properties properties) throws CacheException {
		return new EhcacheTimestampsRegion(accessStrategyFactory, getCache(regionName), properties, redisson,
				batchOptions, localCachedMapOptions);
	}

	private Ehcache getCache(String name) throws CacheException {
		try {
			Ehcache cache = manager.getEhcache(name);
			if (cache == null) {
				LOG.unableToFindEhCacheConfiguration(name);
				manager.addCache(name);
				cache = manager.getEhcache(name);
				LOG.debug("started EHCache region: " + name);
			}
			return cache;
		} catch (net.sf.ehcache.CacheException e) {
			throw new CacheException(e);
		}

	}

	/**
	 * Load a resource from the classpath.
	 */
	protected URL loadResource(String configurationResourceName) {
		URL url = null;
		if (classLoaderService != null) {
			url = classLoaderService.locateResource(configurationResourceName);
		}
		if (url == null) {
			final ClassLoader standardClassloader = Thread.currentThread().getContextClassLoader();
			if (standardClassloader != null) {
				url = standardClassloader.getResource(configurationResourceName);
			}
			if (url == null) {
				url = AbstractEhcacheRegionFactory.class.getResource(configurationResourceName);
			}
			if (url == null) {
				try {
					url = new URL(configurationResourceName);
				} catch (MalformedURLException e) {
					// ignore
				}
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debugf("Creating EhCacheRegionFactory from a specified resource: %s.  Resolved to URL: %s",
					configurationResourceName, url);
		}
		if (url == null) {

			LOG.unableToLoadConfiguration(configurationResourceName);
		}
		return url;
	}

	/**
	 * Default access-type used when the configured using JPA 2.0 config. JPA 2.0
	 * allows <code>@Cacheable(true)</code> to be attached to an entity without any
	 * access type or usage qualification.
	 * <p/>
	 * We are conservative here in specifying {@link AccessType#READ_WRITE} so as to
	 * follow the mantra of "do no harm".
	 * <p/>
	 * This is a Hibernate 3.5 method.
	 */
	public AccessType getDefaultAccessType() {
		return AccessType.READ_WRITE;
	}

}
