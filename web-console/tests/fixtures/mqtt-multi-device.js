// Test-only transport. Never opens a broker socket or reaches a real aircraft.
class MockClient {
  constructor() {
    this.events = new Map()
    this.connected = false
    window.__mqttClients ??= []
    window.__mqttOut ??= []
    window.__mqttClients.push(this)
    setTimeout(() => { this.connected = true; this.emit('connect') }, 20)
  }
  on(name, callback) {
    this.events.set(name, [...(this.events.get(name) || []), callback])
    return this
  }
  once(name, callback) {
    const once = (...args) => {
      this.events.set(name, this.events.get(name).filter(item => item !== once))
      callback(...args)
    }
    return this.on(name, once)
  }
  emit(name, ...args) { for (const callback of [...(this.events.get(name) || [])]) callback(...args) }
  subscribe(topics, options, callback) { callback(null, topics.map(topic => ({ topic, qos: 0 }))) }
  publish(topic, payload, options, callback) {
    const message = JSON.parse(payload)
    window.__mqttOut.push({ topic, ...message })
    callback?.()
    if (message.method === 'heart_beat' || message.method === 'drone_control') {
      setTimeout(() => {
        if (!this.connected) return
        this.emit('message', topic.replace('/down', '/up'), new TextEncoder().encode(JSON.stringify({
          tid: message.tid, bid: message.bid, method: message.method,
          data: { result: 0, output: message.data }
        })))
      }, 5)
    }
  }
  end(force, options, callback) { this.connected = false; this.emit('close'); callback?.() }
}
export default { connect: () => new MockClient() }
