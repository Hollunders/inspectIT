package rocks.inspectit.server.processor.impl;

import java.util.List;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import rocks.inspectit.server.dao.impl.TimerDataAggregator;
import rocks.inspectit.server.processor.AbstractCmrDataProcessor;
import rocks.inspectit.shared.all.communication.DefaultData;
import rocks.inspectit.shared.all.communication.data.HttpInfo;
import rocks.inspectit.shared.all.communication.data.HttpTimerData;
import rocks.inspectit.shared.all.communication.data.TimerData;
import rocks.inspectit.shared.all.serializer.SerializationException;
import rocks.inspectit.shared.all.serializer.impl.SerializationManager;
import rocks.inspectit.shared.all.serializer.provider.SerializationManagerProvider;
import rocks.inspectit.shared.all.spring.logger.Log;

/**
 * Processor that saves {@link TimerData} or {@link HttpTimerData} to database correctly if the
 * charting is on.
 *
 * @author Ivan Senic
 *
 */
public class TimerDataChartingCmrProcessor extends AbstractCmrDataProcessor {

	/**
	 * Log for this class.
	 */
	@Log
	Logger log;

	/**
	 * {@link TimerDataAggregator} for {@link TimerData} aggregation.
	 */
	@Autowired
	TimerDataAggregator timerDataAggregator;

	/**
	 * Serialization manager provider for getting the {@link SerializationManager}.
	 */
	@Autowired
	private SerializationManagerProvider serializationManagerProvider;

	/**
	 * If writing to the influxDB is active. In that case we will not persist anything to the
	 * relational database.
	 */
	@Value("${influxdb.active}")
	boolean influxActive;

	/**
	 * {@link SerializationManager} for cloning.
	 */
	SerializationManager serializationManager;

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void processData(DefaultData defaultData, EntityManager entityManager) {
		if (defaultData instanceof HttpTimerData) {
			try {
				HttpTimerData original = (HttpTimerData) defaultData;
				HttpInfo httpInfo = getHttpInfo(original, entityManager);
				HttpTimerData clone = getClone(original);
				clone.setHttpInfo(httpInfo);
				entityManager.persist(clone);
			} catch (SerializationException e) {
				log.warn("TimerDataChartingCmrProcessor failed to clone the given HttpTimerData", e);
			}
		} else {
			timerDataAggregator.processTimerData((TimerData) defaultData);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean canBeProcessed(DefaultData defaultData) {
		return !influxActive && (defaultData instanceof TimerData) && ((TimerData) defaultData).isCharting();
	}

	/**
	 * Creates the cloned {@link HttpTimerData} by using the kryo and {@link #serializationManager}.
	 * Sets id of the clone to zero.
	 *
	 * @param original
	 *            Data to be cloned.
	 * @return Cloned {@link HttpTimerData} with id zero.
	 * @throws SerializationException
	 *             If serialization fails.
	 */
	private synchronized HttpTimerData getClone(HttpTimerData original) throws SerializationException {
		HttpTimerData httpTimerData = serializationManager.copy(original);
		httpTimerData.setId(0L);
		return httpTimerData;
	}

	/**
	 * Find {@link HttpInfo} to attach to {@link HttpTimerData} when saving.
	 *
	 * @param httpTimerData
	 *            {@link HttpTimerData} to find info for.
	 * @param entityManager
	 *            EntityManager
	 * @return {@link HttpInfo}.
	 */
	private HttpInfo getHttpInfo(HttpTimerData httpTimerData, EntityManager entityManager) {
		HttpInfo httpInfo = httpTimerData.getHttpInfo();
		String uri = httpInfo.isUriDefined() ? httpInfo.getUri() : null; // NOPMD
		String tag = httpInfo.hasInspectItTaggingHeader() ? httpInfo.getInspectItTaggingHeaderValue() : null; // NOPMD
		String requestMethod = httpInfo.getRequestMethod();

		CriteriaBuilder builder = entityManager.getCriteriaBuilder();
		CriteriaQuery<HttpInfo> criteria = builder.createQuery(HttpInfo.class);
		Root<? extends HttpInfo> root = criteria.from(HttpInfo.class);

		Predicate uriPredicate;
		Predicate tagPredicate;
		Predicate requestMethodPredicate = builder.equal(root.get("requestMethod"), requestMethod);
		if (null != uri) {
			uriPredicate = builder.equal(root.get("uri"), uri);
		} else {
			uriPredicate = builder.isNull(root.get("uri"));
		}
		if (null != tag) {
			tagPredicate = builder.equal(root.get("inspectItTaggingHeaderValue"), tag);
		} else {
			tagPredicate = builder.isNull(root.get("inspectItTaggingHeaderValue"));
		}

		criteria.where(uriPredicate, tagPredicate, requestMethodPredicate);

		List<?> httpInfoList = entityManager.createQuery(criteria).getResultList();

		if (CollectionUtils.isNotEmpty(httpInfoList)) {
			return (HttpInfo) httpInfoList.get(0);
		} else {
			return new HttpInfo(uri, requestMethod, tag);
		}
	}

	/**
	 * Post construct.
	 */
	@PostConstruct
	protected void init() {
		serializationManager = serializationManagerProvider.createSerializer();
	}

}
