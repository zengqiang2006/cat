package com.dianping.cat.consumer.metric;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.unidal.lookup.annotation.Inject;
import org.unidal.lookup.util.StringUtils;

import com.dianping.cat.Cat;
import com.dianping.cat.Constants;
import com.dianping.cat.analysis.AbstractMessageAnalyzer;
import com.dianping.cat.configuration.NetworkInterfaceManager;
import com.dianping.cat.consumer.advanced.dal.BusinessReport;
import com.dianping.cat.consumer.advanced.dal.BusinessReportDao;
import com.dianping.cat.consumer.metric.model.entity.MetricItem;
import com.dianping.cat.consumer.metric.model.entity.MetricReport;
import com.dianping.cat.consumer.metric.model.entity.Segment;
import com.dianping.cat.consumer.metric.model.transform.DefaultNativeBuilder;
import com.dianping.cat.consumer.metric.model.transform.DefaultSaxParser;
import com.dianping.cat.consumer.metric.model.transform.DefaultXmlBuilder;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Metric;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.spi.MessageTree;
import com.dianping.cat.storage.Bucket;
import com.dianping.cat.storage.BucketManager;
import com.dianping.cat.task.TaskManager;
import com.dianping.cat.task.TaskManager.TaskProlicy;

public class MetricAnalyzer extends AbstractMessageAnalyzer<MetricReport> implements LogEnabled {
	public static final String ID = "metric";

	@Inject
	private BucketManager m_bucketManager;

	@Inject
	private BusinessReportDao m_businessReportDao;

	@Inject
	private MetricConfigManager m_configManager;

	@Inject
	private ProductLineConfigManager m_productLineConfigManager;

	@Inject
	private TaskManager m_taskManager;

	// key is project line,such as tuangou
	private Map<String, MetricReport> m_reports = new HashMap<String, MetricReport>();

	private static final String METRIC = "Metric";

	@Override
	public void doCheckpoint(boolean atEnd) {
		storeReports(atEnd);
	}

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	public MetricReport getReport(String product) {
		MetricReport report = m_reports.get(product);

		if (report == null) {
			report = new MetricReport(product);

			report.setStartTime(new Date(m_startTime));
			report.setEndTime(new Date(m_startTime + MINUTE * 60 - 1));
		}
		return report;
	}

	protected void loadReports() {
		Bucket<String> reportBucket = null;

		try {
			reportBucket = m_bucketManager.getReportBucket(m_startTime, MetricAnalyzer.ID);

			for (String id : reportBucket.getIds()) {
				String xml = reportBucket.findById(id);
				MetricReport report = DefaultSaxParser.parse(xml);

				m_reports.put(report.getProduct(), report);
			}
		} catch (Exception e) {
			m_logger.error(String.format("Error when loading metric reports of %s!", new Date(m_startTime)), e);
		} finally {
			if (reportBucket != null) {
				m_bucketManager.closeBucket(reportBucket);
			}
		}
	}

	private ConfigItem parseValue(String status, String data) {
		ConfigItem config = new ConfigItem();

		if ("C".equals(status)) {
			int count = Integer.parseInt(data);

			config.setCount(count);
			config.setValue((double) count);
			config.setShowCount(true);
		} else if ("T".equals(status)) {
			double duration = Double.parseDouble(data);

			config.setCount(1);
			config.setValue(duration);
			config.setShowAvg(true);
		} else if ("S".equals(status)) {
			double sum = Double.parseDouble(data);

			config.setCount(1);
			config.setValue(sum);
			config.setShowSum(true);
		} else if ("S,C".equals(status)) {
			String[] datas = data.split(",");

			config.setCount(Integer.parseInt(datas[0]));
			config.setValue(Double.parseDouble(datas[1]));
			config.setShowCount(false);
			config.setShowSum(true);
		} else {
			return null;
		}

		return config;
	}

	@Override
	public void process(MessageTree tree) {
		String domain = tree.getDomain();
		String product = m_productLineConfigManager.queryProductLineByDomain(domain);
		MetricReport report = m_reports.get(product);

		if (report == null) {
			report = new MetricReport(product);
			report.setStartTime(new Date(m_startTime));
			report.setEndTime(new Date(m_startTime + MINUTE * 60 - 1));

			m_reports.put(product, report);
		}

		Message message = tree.getMessage();

		if (message instanceof Transaction) {
			processTransaction(product, report, tree, (Transaction) message);
		} else if (message instanceof Metric) {
			processMetric(product, report, tree, (Metric) message);
		}
	}

	private int processMetric(String group, MetricReport report, MessageTree tree, Metric metric) {
		String type = metric.getType();
		String metricName = metric.getName();
		String domain = tree.getDomain();
		String data = (String) metric.getData();
		String status = metric.getStatus();
		ConfigItem config = parseValue(status, data);

		if (!StringUtils.isEmpty(type)) {
			m_productLineConfigManager.insertIfNotExsit(type, domain);
		}
		if (config != null) {
			long current = metric.getTimestamp() / 1000 / 60;
			int min = (int) (current % (60));
			String key = m_configManager.buildMetricKey(domain, METRIC, metricName);
			MetricItem metricItem = report.findOrCreateMetricItem(key);

			metricItem.addDomain(domain).setType(status);
			updateMetric(metricItem, min, config.getCount(), config.getValue());

			config.setTitle(metricName);

			insertDefaultConfig(metricName, domain, config);
		}
		return 0;
	}

	private void insertDefaultConfig(String metricName, String domain, ConfigItem config) {
		//外部监控Key不需要存储
		if (!Constants.BROKER_SERVICE.equals(domain)) {
	   	m_configManager.insertIfNotExist(domain, METRIC, metricName, config);
	   }
   }

	private int processTransaction(String group, MetricReport report, MessageTree tree, Transaction t) {
		int count = 0;
		List<Message> children = t.getChildren();

		for (Message child : children) {
			if (child instanceof Transaction) {
				count += processTransaction(group, report, tree, (Transaction) child);
			} else if (child instanceof Metric) {
				count += processMetric(group, report, tree, (Metric) child);
			}
		}

		return count;
	}

	public void setBucketManager(BucketManager bucketManager) {
		m_bucketManager = bucketManager;
	}

	public void setBusinessReportDao(BusinessReportDao businessReportDao) {
		m_businessReportDao = businessReportDao;
	}

	public void setConfigManager(MetricConfigManager configManager) {
		m_configManager = configManager;
	}

	public void setProductLineConfigManager(ProductLineConfigManager productLineConfigManager) {
		m_productLineConfigManager = productLineConfigManager;
	}

	public void setTaskManager(TaskManager taskManager) {
		m_taskManager = taskManager;
	}

	protected void storeReports(boolean atEnd) {
		Bucket<String> reportBucket = null;
		Transaction t = Cat.getProducer().newTransaction("Checkpoint", ID);

		t.setStatus(Message.SUCCESS);
		try {
			reportBucket = m_bucketManager.getReportBucket(m_startTime, MetricAnalyzer.ID);

			for (MetricReport report : m_reports.values()) {
				try {
					String xml = new DefaultXmlBuilder(true).buildXml(report);
					String product = report.getProduct();

					reportBucket.storeById(product, xml);
				} catch (Exception e) {
					t.setStatus(e);
					Cat.logError(e);
				}
			}

			if (atEnd && !isLocalMode()) {
				Date period = new Date(m_startTime);
				String ip = NetworkInterfaceManager.INSTANCE.getLocalHostAddress();
				int binary = 1;

				for (MetricReport report : m_reports.values()) {
					try {
						BusinessReport r = m_businessReportDao.createLocal();
						String product = report.getProduct();

						r.setName(ID);
						r.setProductLine(product);
						r.setPeriod(period);
						r.setIp(ip);
						r.setType(binary);
						r.setContent(DefaultNativeBuilder.build(report));
						r.setCreationDate(new Date());

						m_businessReportDao.insert(r);
					} catch (Throwable e) {
						m_logger.error(report.toString());
						t.setStatus(e);
						Cat.getProducer().logError(e);
					}
				}
				m_taskManager.createTask(period, Constants.CAT, ID, TaskProlicy.DAILY);
			}
		} catch (Exception e) {
			Cat.getProducer().logError(e);
			t.setStatus(e);
			m_logger.error(String.format("Error when storing metric reports of %s!", new Date(m_startTime)), e);
		} finally {
			t.complete();

			if (reportBucket != null) {
				m_bucketManager.closeBucket(reportBucket);
			}
		}
	}

	private void updateMetric(MetricItem metricItem, int minute, int count, double sum) {
		Segment seg = metricItem.findOrCreateSegment(minute);

		seg.setCount(seg.getCount() + count);
		seg.setSum(seg.getSum() + sum);
		seg.setAvg(seg.getSum() / seg.getCount());
	}

	public static class ConfigItem {
		private int m_count;

		private double m_value;

		private boolean m_showCount = false;

		private boolean m_showAvg = false;

		private boolean m_showSum = false;

		private String m_title;

		public int getCount() {
			return m_count;
		}

		public String getTitle() {
			return m_title;
		}

		public double getValue() {
			return m_value;
		}

		public boolean isShowAvg() {
			return m_showAvg;
		}

		public boolean isShowCount() {
			return m_showCount;
		}

		public boolean isShowSum() {
			return m_showSum;
		}

		public ConfigItem setCount(int count) {
			m_count = count;
			return this;
		}

		public ConfigItem setShowAvg(boolean showAvg) {
			m_showAvg = showAvg;
			return this;
		}

		public ConfigItem setShowCount(boolean showCount) {
			m_showCount = showCount;
			return this;
		}

		public ConfigItem setShowSum(boolean showSum) {
			m_showSum = showSum;
			return this;
		}

		public void setTitle(String title) {
			m_title = title;
		}

		public ConfigItem setValue(double value) {
			m_value = value;
			return this;
		}
	}

}
