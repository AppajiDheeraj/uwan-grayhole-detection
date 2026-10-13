import groovy.transform.CompileStatic
import org.arl.unet.sim.Reception
import org.arl.unet.sim.channels.ProtocolChannelModel

/**
 * The project's acoustic channel: UnetStack's ProtocolChannelModel with natural loss that
 * grows with distance, so far links drop more frames than near ones even when every relay
 * is honest. Every simulation script just sets  channel.model = DistanceLossChannel.
 *   P(decode) = 1 - edgeLoss * (d / range)^2,   range 1500 m,  edgeLoss 0.15
 */
@CompileStatic  // static calls: the simulator's Reception class is not public, so dynamic Groovy dispatch fails
class DistanceLossChannel extends ProtocolChannelModel {

  float edgeLoss = (float) Detectors.EDGE_LOSS   // loss at the edge of range (the detectors assume the same curve)

  DistanceLossChannel() {
    communicationRange = detectionRange = interferenceRange = (float) Topology.RANGE
    pDecoding = 1.0f
  }

  @Override
  protected float getProbabilityDecoding(Reception rx) {
    double r = rx.range / communicationRange
    return (float)(super.getProbabilityDecoding(rx) * (1 - edgeLoss * r * r))
  }
}
