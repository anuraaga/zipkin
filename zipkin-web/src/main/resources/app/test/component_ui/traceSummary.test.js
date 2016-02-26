import {
  Span,
  traceSummary,
  getServiceName,
  traceSummariesToMustache,
  Constants
} from '../../js/component_ui/traceSummary';

chai.config.truncateThreshold = 0;

function endpoint(ipv4, port, serviceName) {
  return {ipv4, port, serviceName};
}

function annotation(timestamp, value, endpoint) {
  return {timestamp, value, endpoint};
}

function span(traceId, name, id, parentId = null, timestamp = null, duration = null, annotations = [], binaryAnnotations = [], debug = false) {
  return {
    traceId,
    name,
    id,
    parentId,
    timestamp,
    duration,
    annotations,
    binaryAnnotations,
    debug
  };
}

const ep1 = endpoint(123, 123, 'service1');
const ep2 = endpoint(456, 456, 'service2');
const ep3 = endpoint(666, 666, 'service2');
const ep4 = endpoint(777, 777, 'service3');
const ep5 = endpoint(888, 888, 'service3');

describe('traceSummary', () => {
  const annotations1 = [
    annotation(100, Constants.CLIENT_SEND, ep1),
    annotation(150, Constants.CLIENT_RECEIVE, ep1)
  ];
  const annotations2 = [
    annotation(200, Constants.CLIENT_SEND, ep2),
    annotation(250, Constants.CLIENT_RECEIVE, ep2)
  ];
  const annotations3 = [
    annotation(300, Constants.CLIENT_SEND, ep2),
    annotation(350, Constants.CLIENT_RECEIVE, ep3)
  ];
  const annotations4 = [
    annotation(400, Constants.CLIENT_SEND, ep4),
    annotation(500, Constants.CLIENT_RECEIVE, ep5)
  ];

  const span1Id = '666';
  const span2Id = '777';
  const span3Id = '888';
  const span4Id = '999';
  const span5Id = '1111';

  const span1 = span(12345, 'methodcall1', span1Id, null, 100, 50, annotations1);
  const span2 = span(12345, 'methodcall2', span2Id, span1Id, 200, 50, annotations2);
  const span3 = span(12345, 'methodcall2', span3Id, span2Id, 300, 50, annotations3);
  const span4 = span(12345, 'methodcall2', span4Id, span3Id, 400, 100, annotations4);
  const span5 = span(12345, 'methodcall4', span5Id, span4Id);

  const trace = [span1, span2, span3, span4];

  it('should return null when no spans exist', () => {
    expect(traceSummary([])).to.equal(null);
  });

  it('should return null when no annotations are present', () => {
    expect(traceSummary([span5])).to.equal(null);
  });

  it('dedupes duplicate endpoints', () => {
    const summary = traceSummary(trace);
    summary.endpoints.should.eql([ep1, ep2, ep3, ep4, ep5]);
  });

  it('calculates timestamp and duration', () => {
    const summary = traceSummary(trace);
    summary.timestamp.should.equal(100);
    summary.duration.should.equal(400);
  });
});

describe('get service name of a span', () => {
  it('should get service name from server addr', () => {
    const span = {
      binaryAnnotations: [{
        key: Constants.SERVER_ADDR,
        value: 'something',
        endpoint: {
          serviceName: 'user-service'
        }
      }]
    };
    getServiceName(span).should.equal('user-service');
  });

  it('should get service name from some server annotation', () => {
    const span = {
      binaryAnnotations: [],
      annotations: [{
        value: Constants.SERVER_RECEIVE_FRAGMENT,
        endpoint: {
          serviceName: 'test-service'
        }
      }]
    };
    getServiceName(span).should.equal('test-service');
  });

  it('should get service name from client addr', () => {
    const span = {
      binaryAnnotations: [{
        key: Constants.CLIENT_ADDR,
        value: 'something',
        endpoint: {
          serviceName: 'my-service'
        }
      }]
    };
    getServiceName(span).should.equal('my-service');
  });

  it('should get service name from client annotation', () => {
    const span = {
      annotations: [{
        value: Constants.CLIENT_SEND,
        endpoint: {
          serviceName: 'abc-service'
        }
      }]
    };
    getServiceName(span).should.equal('abc-service');
  });

  it('should get service name from local component annotation', () => {
    const span = {
      binaryAnnotations: [{
        key: Constants.LOCAL_COMPONENT,
        value: 'something',
        endpoint: {
          serviceName: 'localservice'
        }
      }]
    };
    getServiceName(span).should.equal('localservice');
  });
});

describe('traceSummariesToMustache', () => {
  const start = 1456447911000000;
  const summary = {
    traceId: 'cafedead',
    timestamp: start,
    duration: 20000,
    spanTimestamps: [{
      name: 'A',
      timestamp: start,
      duration: 10000
    }, {
      name: 'B',
      timestamp: start + 1000,
      duration: 20000
    }, {
      name: 'B',
      timestamp: start + 1000,
      duration: 15000
    }],
    endpoints: [ep1, ep2]
  };

  it('should return empty list for empty list', () => {
    traceSummariesToMustache(null, []).should.eql([]);
  });

  it('should convert duration from micros to millis', () => {
    const model = traceSummariesToMustache(null, [{duration: 3000}]);
    model[0].duration.should.equal(3);
  });

  it('should get service durations', () => {
    const model = traceSummariesToMustache(null, [summary]);
    model[0].serviceDurations.should.eql([{
      name: 'A',
      count: 1,
      max: 10
    }, {
      name: 'B',
      count: 2,
      max: 20
    }]);
  });

  it('should pass on the trace id', () => {
    const model = traceSummariesToMustache('A', [summary]);
    model[0].traceId.should.equal(summary.traceId);
  });

  it('should get service percentage', () => {
    const model = traceSummariesToMustache('A', [summary]);
    model[0].servicePercentage.should.equal(50);
  });

  it('should get span count', () => {
    const model = traceSummariesToMustache(null, [summary]);
    model[0].spanCount.should.equal(3);
  });

  it('should format start time', () => {
    const model = traceSummariesToMustache(null, [summary], true);
    model[0].startTs.should.equal('02-26-2016T00:51:51.000+0000');
  });

  it('should format duration', () => {
    const model = traceSummariesToMustache(null, [summary]);
    model[0].durationStr.should.equal('20.000ms');
  });

  it('should calculate the width in percent', () => {
    const model = traceSummariesToMustache(null, [summary]);
    model[0].width.should.equal(100);
  });

  it('should pass on timestamp', () => {
    const model = traceSummariesToMustache(null, [summary]);
    model[0].timestamp.should.equal(summary.timestamp);
  });
});
