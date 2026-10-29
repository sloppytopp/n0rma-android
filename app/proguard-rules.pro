# N0RMA uses no reflection or serialization libraries; the default optimize rules are enough.

# The optional SDR build loads its USB driver by name (SdrBackend), so R8 must not rename or remove it.
-keep class io.github.sloppytopp.n0rma.sdr.UsbRtlDriver { <init>(); }
