export class WhepPlayer {
  private peer?: RTCPeerConnection
  private resourceUrl?: string

  async play(video: HTMLVideoElement, endpoint: string) {
    await this.stop()
    const peer = new RTCPeerConnection()
    this.peer = peer
    peer.addTransceiver('video', { direction: 'recvonly' })
    peer.addTransceiver('audio', { direction: 'recvonly' })
    peer.ontrack = (event) => {
      video.srcObject = event.streams[0] ?? new MediaStream([event.track])
      void video.play()
    }
    peer.onconnectionstatechange = () => {
      if (peer.connectionState === 'failed') void this.stop()
    }

    const offer = await peer.createOffer()
    await peer.setLocalDescription(offer)
    await waitForIce(peer)
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/sdp' },
      body: peer.localDescription?.sdp
    })
    if (!response.ok) {
      await this.stop()
      throw new Error(`WHEP 建链失败：HTTP ${response.status}`)
    }
    const location = response.headers.get('Location')
    if (location) this.resourceUrl = new URL(location, new URL(endpoint, window.location.href)).toString()
    await peer.setRemoteDescription({ type: 'answer', sdp: await response.text() })
  }

  async stop() {
    const resource = this.resourceUrl
    this.resourceUrl = undefined
    if (resource) await fetch(resource, { method: 'DELETE' }).catch(() => undefined)
    this.peer?.close()
    this.peer = undefined
  }
}

function waitForIce(peer: RTCPeerConnection) {
  if (peer.iceGatheringState === 'complete') return Promise.resolve()
  return new Promise<void>((resolve) => {
    const timeout = window.setTimeout(done, 2500)
    function done() {
      window.clearTimeout(timeout)
      peer.removeEventListener('icegatheringstatechange', change)
      resolve()
    }
    function change() {
      if (peer.iceGatheringState === 'complete') done()
    }
    peer.addEventListener('icegatheringstatechange', change)
  })
}
