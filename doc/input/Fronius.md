# Using the smart meter of a Fronius Symo inverter as the input source

A Fronius smart meter attached to a Fronius Symo inverter can be read via the Modbus TCP interface of
the inverter (SunSpec float meter model). To use it as an input source, set up the `uni-meter.conf`
file as below. Adjust the IP address according to your setup:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.fronius-symo"

  input-devices {
    fronius-symo {
      address = "192.168.178.125"
      port = 502
      unit-id = 240
    }
  }
}
```

## Register addressing

Notably, the documented register numbers are addressed by the inverter in a non-standard way: the
registers are served at absolute protocol addresses, so the protocol address of a register is the
register number minus one (e.g. the power values documented as the registers 40089 - 40091 are
served at the protocol addresses 40088 - 40090). Requesting addresses based on the usual
`register - 40001` offset convention fails with an `ILLEGAL_DATA_ADDRESS` exception.

## Polling frequency

The inverter answers Modbus requests quite slowly (round-trip times of 400 - 500 ms are typical),
and the next polling cycle starts only after the response has been received and the configured
`polling-interval` (1s by default) has elapsed. The effective update interval thus adds up to
`polling-interval` + response time, so roughly 1.5s with the default settings. If faster updates
are desired, reduce the `polling-interval` accordingly:

```hocon
uni-meter {
  input-devices {
    fronius-symo {
      # ...
      polling-interval = 500ms
    }
  }
}
```

## Prerequisites

* The Modbus TCP interface of the inverter must be activated. To do this, either use the inverter's
  display / web interface and set *Modbus* to *on* (the "SunSpec" register mapping with integer
  values is used).
* The smart meter is exposed with unit-id 240 on Fronius inverters prior to the GEN24 series
  (e.g. Symo, Galvo, Primo).
* If the power values have an inverted sign in your setup (e.g. because of the meter variant or the
  mounting direction), set `invert-power = true`.

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"

  input = "uni-meter.input-devices.generic-http"

  input-devices {
    generic-http {
      url = "http://192.168.x.x/solar_api/v1/GetMeterRealtimeData.cgi?Scope=System"

      power-phase-mode = "tri-phase"

      channels = [{
        type = "json"
        channel = "power-l1"
        json-path = "$.Body.Data.0.PowerReal_P_Phase_1"
      },{
        type = "json"
        channel = "power-l2"
        json-path = "$.Body.Data.0.PowerReal_P_Phase_2"
      },{
        type = "json"
        channel = "power-l3"
        json-path = "$.Body.Data.0.PowerReal_P_Phase_3"
      }]
    }
  }
}
```
