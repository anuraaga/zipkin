import _ from 'lodash';
import moment from 'moment';

const CLIENT_SEND = 'cs';
const CLIENT_SEND_FRAGMENT = 'csf';
const CLIENT_RECEIVE = 'cr';
const CLIENT_RECEIVE_FRAGMENT = 'crf';
const SERVER_SEND = 'ss';
const SERVER_SEND_FRAGMENT = 'ssf';
const SERVER_RECEIVE = 'sr';
const SERVER_RECEIVE_FRAGMENT = 'srf';
const SERVER_ADDR = 'sa';
const CLIENT_ADDR = 'ca';
const LOCAL_COMPONENT = 'lc';
const CORE_CLIENT = [CLIENT_RECEIVE, CLIENT_RECEIVE_FRAGMENT, CLIENT_SEND, CLIENT_SEND_FRAGMENT];
const CORE_SERVER = [SERVER_RECEIVE, SERVER_RECEIVE_FRAGMENT, SERVER_SEND, SERVER_SEND_FRAGMENT];
export const Constants = {
  CLIENT_SEND,
  CLIENT_SEND_FRAGMENT,
  CLIENT_RECEIVE,
  CLIENT_RECEIVE_FRAGMENT,
  SERVER_SEND,
  SERVER_SEND_FRAGMENT,
  SERVER_RECEIVE,
  SERVER_RECEIVE_FRAGMENT,
  SERVER_ADDR,
  CLIENT_ADDR,
  CORE_CLIENT,
  CORE_SERVER,
  LOCAL_COMPONENT
};

function endpointsForSpan(span) {
  return _.union(
    span.annotations.map(a => a.endpoint),
    span.binaryAnnotations.map(a => a.endpoint)
  ).filter(h => h != null);
}

// What's the total duration of the spans in this trace?
function traceDuration(spans) {
  // turns (timestamp, timestamp + duration) into an ordered list
  const timestamps = _(spans).flatMap(({timestamp, duration}) => timestamp ?
    (duration ?
      [timestamp, timestamp + duration]
      :
      [timestamp]
    )
    : []
  ).sort().value();

  if (timestamps.length < 2) {
    return null;
  } else {
    const first = _.head(timestamps);
    const last = _.last(timestamps);
    return last - first;
  }
}

function getServiceNames(span) {
  return _(endpointsForSpan(span)).uniqWith(endpointEquals).map((ep) => ep.serviceName).filter((name) => name != null && name != '').value();
}

export function getServiceName(span) {
  // Most authoritative is the label of the server's endpoint
  const annotationFromServerAddr = _(span.binaryAnnotations || []).find((ann) => ann.key === Constants.SERVER_ADDR && ann.endpoint != null && ann.endpoint.serviceName != null && ann.endpoint.serviceName != '');
  const serviceNameFromServerAddr = annotationFromServerAddr ? annotationFromServerAddr.endpoint.serviceName : null;
  if (serviceNameFromServerAddr) {
    return serviceNameFromServerAddr;
  }

  // Next, the label of any server annotation, logged by an instrumented server
  const annotationFromServerSideAnnotations = _(span.annotations || []).find((ann) => Constants.CORE_SERVER.indexOf(ann.value) !== -1 && ann.endpoint != null && ann.endpoint.serviceName != null && ann.endpoint.serviceName != '');
  const serviceNameFromServerSideAnnotation = annotationFromServerSideAnnotations? annotationFromServerSideAnnotations.endpoint.serviceName : null;
  if (serviceNameFromServerSideAnnotation) {
    return serviceNameFromServerSideAnnotation;
  }

  // Next is the label of the client's endpoint
  const annotationFromClientAddr = _(span.binaryAnnotations || []).find((ann) => ann.key === Constants.CLIENT_ADDR && ann.endpoint != null && ann.endpoint.serviceName != null && ann.endpoint.serviceName != '');
  const serviceNameFromClientAddr = annotationFromClientAddr? annotationFromClientAddr.endpoint.serviceName : null;
  if (serviceNameFromClientAddr) {
    return serviceNameFromClientAddr;
  }

  // Next is the label of any client annotation, logged by an instrumented client
  const annotationFromClientSideAnnotations = _(span.annotations || []).find((ann) => Constants.CORE_CLIENT.indexOf(ann.value) !== -1 && ann.endpoint != null && ann.endpoint.serviceName != null && ann.endpoint.serviceName != '');
  const serviceNameFromClientAnnotation = annotationFromClientSideAnnotations? annotationFromClientSideAnnotations.endpoint.serviceName : null;
  if (serviceNameFromClientAnnotation) {
    return serviceNameFromClientAnnotation;
  }

  // Finally is the label of the local component's endpoint
  const annotationFromLocalComponent = _(span.binaryAnnotations || []).find((ann) => ann.key === Constants.LOCAL_COMPONENT && ann.endpoint != null && ann.endpoint.serviceName != null && ann.endpoint.serviceName != '');
  const serviceNameFromLocalComponent = annotationFromLocalComponent? annotationFromLocalComponent.endpoint.serviceName : null;
  if (serviceNameFromLocalComponent) {
    return serviceNameFromLocalComponent;
  }

  return null;
}

function getSpanTimestamps(spans) {
  return _(spans).flatMap((span) => getServiceNames(span).map((serviceName) => ({
    name: serviceName,
    timestamp: span.timestamp,
    duration: span.duration
  }))).value();
}

function endpointEquals(e1, e2) {
  return e1.ipv4 === e2.ipv4 && e1.port === e2.port && e1.serviceName === e2.serviceName;
}

export function traceSummary(spans = []) {
  if (spans.length === 0 || !spans[0].timestamp) {
    return null;
  } else {
    const duration = traceDuration(spans) || 0;
    const endpoints = _(spans).flatMap(endpointsForSpan).uniqWith(endpointEquals).value();
    const traceId = spans[0].traceId;
    const timestamp = spans[0].timestamp;
    const spanTimestamps = getSpanTimestamps(spans);
    return {
      traceId,
      timestamp,
      duration,
      spanTimestamps,
      endpoints
    };
  }
}

function totalServiceTime(stamps, acc = 0) {
  if (stamps.length == 0) {
    return acc;
  } else {
    const ts = _(stamps).minBy((s) => s.timestamp);
    const [current, next] = _(stamps).partition((t) => t.timestamp >= ts.timestamp && t.timestamp + t.duration <= ts.timestamp + ts.duration).value();
    const endTs = Math.max(...current.map((t) => t.timestamp + t.duration));
    return totalServiceTime(next, acc + (endTs - ts.timestamp));
  }
}

function formatDate(timestamp, utc) {
  let m = moment(timestamp / 1000);
  if (utc) {
    m = m.utc();
  }
  return m.format('MM-DD-YYYYTHH:mm:ss.SSSZZ');

}

export function traceSummariesToMustache(serviceName = null, traceSummaries, utc = false) {
  if (traceSummaries.length === 0) {
    return [];
  } else {
    const maxDuration = Math.max(...traceSummaries.map((s) => s.duration)) / 1000;

    return traceSummaries.map((t) => {
      const duration = t.duration / 1000;
      const groupedTimestamps = _(t.spanTimestamps).groupBy((sts) => sts.name).value();
      const serviceDurations = _(groupedTimestamps).toPairs().map(([name, sts]) => ({
        name,
        count: sts.length,
        max: parseInt(Math.max(...sts.map(t => t.duration)) / 1000)
      })).sortBy('name').value();

      let serviceTime;
      if (!serviceName || !groupedTimestamps[serviceName]) {
        serviceTime = 0;
      } else {
        serviceTime = totalServiceTime(groupedTimestamps[serviceName]);
      }

      const startTs = formatDate(t.timestamp, utc);
      const durationStr = (t.duration / 1000).toFixed(3) + 'ms';
      const servicePercentage = parseInt(parseFloat(serviceTime) / parseFloat(t.duration) * 100);
      const spanCount = _(groupedTimestamps).values().sumBy((sts) => sts.length);
      const width = parseInt(parseFloat(duration) / parseFloat(maxDuration) * 100);

      return {
        traceId: t.traceId,
        startTs,
        timestamp: t.timestamp,
        duration,
        durationStr,
        servicePercentage,
        spanCount,
        serviceDurations,
        width
      };
    }).sort((t1, t2) => t1.duration < t2.duration);
  }
}
