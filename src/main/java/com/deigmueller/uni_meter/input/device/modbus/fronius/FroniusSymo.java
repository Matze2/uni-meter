package com.deigmueller.uni_meter.input.device.modbus.fronius;

import com.deigmueller.uni_meter.input.device.modbus.Modbus;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

/**
 * Input device reading the measurement values of a Fronius smart meter attached to a Fronius Symo inverter.
 *
 * The inverter exposes the smart meter data via its Modbus TCP interface using the SunSpec integer meter
 * model (model 203). The data is published as 16 bit big endian signed integers. Only the power values of
 * the phases L1 - L3 are read (holding registers 40089 - 40091) and published in every polling cycle, all
 * other output values (voltage, frequency, power factor, current) are derived by the output device. The
 * register 40092 contains a scaling exponent, which is applied to the power values (power * 10^exponent).
 *
 * The unit-id of the smart meter is 240 for Fronius inverters prior to the GEN24 series.
 */
public class FroniusSymo extends Modbus {
  // Class members
  public static final String TYPE = "FroniusSymo";

  // Modbus register numbers (one based as documented by Fronius)
  private static final int REGISTER_POWER_L1 = 40089;

  // Instance members
  private final double powerSign = getConfig().getBoolean("invert-power") ? -1.0 : 1.0;

  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new FroniusSymo(context, outputDevice, config));
  }

  protected FroniusSymo(@NotNull ActorContext<Command> context,
                         @NotNull ActorRef<OutputDevice.Command> outputDevice,
                         @NotNull Config config) {
    super(context,
          outputDevice,
          config.withFallback(context.getSystem().settings().config().getConfig("uni-meter.input-devices.modbus")));
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(ReadPowersSucceeded.class, this::onReadPowersSucceeded);
  }

  @Override
  protected @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("FroniusSymo.onConnectSucceeded()");
    super.onConnectSucceeded(message);

    readPowers();

    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onReadPowersSucceeded(@NotNull ReadPowersSucceeded message) {
    logger.trace("FroniusSymo.onReadPowersSucceeded()");

    try {
      ByteBuffer byteBuffer = ByteBuffer.wrap(message.response().registers());

      double rawPowerL1 = byteBuffer.getShort();
      double rawPowerL2 = byteBuffer.getShort();
      double rawPowerL3 = byteBuffer.getShort();

      // The register 40092 contains a scaling exponent applied to the power values (power * 10^exponent)
      double scale = Math.pow(10, byteBuffer.getShort());

      double powerL1 = powerSign * rawPowerL1 * scale;
      double powerL2 = powerSign * rawPowerL2 * scale;
      double powerL3 = powerSign * rawPowerL3 * scale;

      logger.debug("Power L1 found (W): {}", powerL1);
      logger.debug("Power L2 found (W): {}", powerL2);
      logger.debug("Power L3 found (W): {}", powerL3);

      notifyPowerData(powerL1, powerL2, powerL3);
    } catch (Exception exception) {
      logger.error("failed to convert power data", exception);
    }

    startNextPollingTimer();

    return Behaviors.same();
  }

  @Override
  protected @NotNull Behavior<Command> onStartNextPollingCycle(@NotNull StartNextPollingCycle message) {
    logger.trace("FroniusSymo.onStartNextPollingCycle()");

    readPowers();

    return Behaviors.same();
  }

  private void readPowers() {
    logger.trace("FroniusSymo.readPowers()");

    // Read the power values of the phases L1 - L3 and the scaling exponent.
    // The inverter serves its SunSpec register map at absolute protocol addresses: the documented
    // register numbers are 1-based, so the protocol address of a register is the register number - 1
    // (e.g. register 40089 -> address 40088), as confirmed by a working mbtools wire log (requesting
    // address 40087 returns the values of the registers 40088 - 40092).
    getClient()
        .readHoldingRegistersAsync(getUnitId(), new ReadHoldingRegistersRequest(REGISTER_POWER_L1 - 1, 4))
        .whenComplete((response, throwable) -> {
          if (throwable != null) {
            getContext().getSelf().tell(new ReadHoldingRegistersFailed(REGISTER_POWER_L1, 4, throwable));
          } else {
            getContext().getSelf().tell(new ReadPowersSucceeded(response));
          }
        });
  }

  public record ReadPowersSucceeded(
        @NotNull ReadHoldingRegistersResponse response
  ) implements Command {}
}
