// launcher.cs — Trinh khoi dong he thong cham thi (Grader).
// Bien dich thanh GraderLauncher.exe: chay build-exe.cmd.
// Giao vien chi viec double-click GraderLauncher.exe -> tu chay start-all.ps1
// (bat MySQL + backend + frontend).
//
// LUU Y: file exe nay la "launcher" - no goi start-all.ps1 ben canh, nen phai
// dat GraderLauncher.exe CUNG THU MUC voi start-all.ps1.

using System;
using System.Diagnostics;
using System.IO;

class Launcher
{
    static int Main(string[] args)
    {
        Console.Title = "Grader - Trinh khoi dong";
        string exeDir = AppDomain.CurrentDomain.BaseDirectory;
        string script = Path.Combine(exeDir, "start-all.ps1");

        if (!File.Exists(script))
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine("[LOI] Khong tim thay start-all.ps1 ben canh file exe.");
            Console.WriteLine("Hay dat GraderLauncher.exe cung thu muc chua start-all.ps1.");
            Console.ResetColor();
            Console.WriteLine("\nNhan phim bat ky de thoat...");
            Console.ReadKey();
            return 1;
        }

        // Chuyen tiep tham so (vd: GraderLauncher.exe -SkipMysql)
        string extra = args.Length > 0 ? " " + string.Join(" ", args) : "";

        var psi = new ProcessStartInfo
        {
            FileName = "powershell.exe",
            Arguments = "-NoProfile -ExecutionPolicy Bypass -File \"" + script + "\"" + extra,
            WorkingDirectory = exeDir,
            UseShellExecute = false
        };

        Console.WriteLine("Dang khoi dong he thong cham thi...\n");
        try
        {
            using (var p = Process.Start(psi))
            {
                p.WaitForExit();
            }
        }
        catch (Exception ex)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine("[LOI] Khong chay duoc PowerShell: " + ex.Message);
            Console.ResetColor();
        }

        Console.WriteLine();
        Console.WriteLine("Cac cua so dich vu da mo (backend/frontend).");
        Console.WriteLine("Mo trinh duyet: http://localhost:3000");
        Console.WriteLine("Co the dong cua so nay. Nhan phim bat ky de thoat...");
        Console.ReadKey();
        return 0;
    }
}
