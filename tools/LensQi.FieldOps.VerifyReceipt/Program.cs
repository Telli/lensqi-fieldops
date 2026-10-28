// verify-receipt: recompute every digest in an issued FieldProof receipt bundle.
//   dotnet run --project tools/LensQi.FieldOps.VerifyReceipt -- <bundle-directory>
// Exit 0 when every artifact and the manifest digest match; 1 when anything was altered or is missing.
// It proves bundle integrity only: it cannot prove who captured the images or that the whole bundle was not replaced.

using LensQi.FieldOps.Core.Receipts;

if (args.Length != 1 || !Directory.Exists(args[0]))
{
    Console.Error.WriteLine("usage: verify-receipt <bundle-directory containing receipt.json>");
    return 2;
}

var result = ReceiptVerifier.Verify(args[0]);
foreach (var c in result.Checks) Console.WriteLine($"  ok   {c}");
foreach (var f in result.Failures) Console.WriteLine($"  FAIL {f}");
Console.WriteLine(result.IsValid ? "RECEIPT INTEGRITY: PASS" : "RECEIPT INTEGRITY: FAIL (bundle altered or incomplete)");
return result.IsValid ? 0 : 1;
