import React from 'react';
import { ArrowLeft, ChevronLeft, ChevronRight, CheckCircle, Clock, AlertTriangle } from 'lucide-react';
import Link from 'next/link';

interface WorkspaceHeaderProps {
  student: {
    id: string;
    name: string;
    status: string;
  };
  totalScore: number;
  maxTotal: number;
}

export default function WorkspaceHeader({ student, totalScore, maxTotal }: WorkspaceHeaderProps) {
  return (
    <header className="h-16 bg-white border-b border-slate-200 flex items-center justify-between px-4 shrink-0">
      <div className="flex items-center gap-4">
        <Link href="/teacher" className="p-2 hover:bg-slate-100 rounded-md text-slate-500 transition-colors">
          <ArrowLeft size={20} />
        </Link>
        <div className="h-6 w-px bg-slate-200"></div>
        <div>
          <h1 className="text-sm font-semibold text-slate-800">Kỳ thi: Flutter Cơ bản (PE_06)</h1>
          <div className="flex items-center gap-2 text-xs text-slate-500 mt-0.5">
            <span className="font-medium text-blue-600">{student.id}</span>
            <span>•</span>
            <span>{student.name}</span>
          </div>
        </div>
      </div>

      <div className="flex items-center gap-6">
        <div className="flex items-center gap-2 bg-slate-100 rounded-full px-3 py-1 text-sm font-medium">
          {student.status === 'GRADED' && <CheckCircle size={16} className="text-emerald-500" />}
          {student.status === 'PENDING' && <Clock size={16} className="text-amber-500" />}
          {student.status === 'ERROR' && <AlertTriangle size={16} className="text-red-500" />}
          <span className="text-slate-700">
            {student.status === 'PENDING' ? 'Chưa chấm' : student.status === 'GRADED' ? 'Đã chấm' : 'Có lỗi'}
          </span>
        </div>

        <div className="flex items-center gap-1">
          <button className="p-1.5 text-slate-400 hover:text-slate-700 hover:bg-slate-100 rounded transition-colors disabled:opacity-50">
            <ChevronLeft size={20} />
          </button>
          <span className="text-sm font-medium text-slate-600 w-16 text-center">5 / 32</span>
          <button className="p-1.5 text-slate-400 hover:text-slate-700 hover:bg-slate-100 rounded transition-colors">
            <ChevronRight size={20} />
          </button>
        </div>

        <div className="h-6 w-px bg-slate-200"></div>

        <div className="flex items-center gap-3">
          <div className="text-right">
            <div className="text-xs text-slate-500 font-medium">Tổng điểm</div>
            <div className="text-lg font-bold text-slate-800 leading-none mt-0.5">
              {totalScore.toFixed(1)} <span className="text-sm text-slate-400 font-normal">/ {maxTotal}</span>
            </div>
          </div>
        </div>
      </div>
    </header>
  );
}
