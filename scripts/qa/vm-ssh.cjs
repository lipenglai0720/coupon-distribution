module.exports = function vmSshArguments() {
  const target = process.env.COUPON_VM_SSH_TARGET;
  if (!target || target.startsWith("-")) {
    throw new Error("Set COUPON_VM_SSH_TARGET to a known SSH host or configured alias");
  }
  const identity = process.env.COUPON_VM_IDENTITY;
  return [...(identity ? ["-i", identity] : []), "-o", "BatchMode=yes", target];
};
