import groovy.transform.CompileStatic
import org.arl.unet.sim.Reception
import org.arl.unet.sim.channels.ProtocolChannelModel

/**
 * ProtocolChannelModel with natural loss that grows with distance, so far links
 * drop more frames than near ones even when every relay is honest.
 *   P(decode) = pDecoding * (1 - edgeLoss * (d / communicationRange)^2)
 */
@CompileStatic  // static calls: the simulator's Reception class is not public, so dynamic Groovy dispatch fails
class DistanceLossChannel extends ProtocolChannelModel {

  float edgeLoss = 0.3f   // extra loss at the edge of communication range

  @Override
  protected float getProbabilityDecoding(Reception rx) {
    double r = rx.range / communicationRange
    return (float)(super.getProbabilityDecoding(rx) * (1 - edgeLoss * r * r))
  }
}
