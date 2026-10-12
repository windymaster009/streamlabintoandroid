class CastAudio extends AudioWorkletProcessor {
  constructor() {
    super();
    this.queue = [];
    this.offset = 0;
    this.count = 0;
    this.port.onmessage = ({ data }) => {
      if (data.reset) { this.queue = []; this.offset = 0; this.count = 0; return; }
      if (!(data.samples instanceof Float32Array)) return;
      this.queue.push(data.samples);
      this.count += data.samples.length;
      // Keep the output near live by discarding stale network audio.
      while (this.count > 9600 && this.queue.length > 1) {
        const head = this.queue.shift();
        this.count -= head.length - this.offset;
        this.offset = 0;
      }
    };
  }
  process(_input, outputs) {
    const channels = outputs[0];
    if (!channels || !channels[0]) return true;
    const output = channels[0];
    for (let i = 0; i < output.length; i++) {
      let value = 0;
      if (this.queue.length) {
        const head = this.queue[0];
        value = head[this.offset++];
        this.count--;
        if (this.offset >= head.length) { this.queue.shift(); this.offset = 0; }
      }
      for (const channel of channels) channel[i] = value;
    }
    return true;
  }
}
registerProcessor('cast-audio', CastAudio);
