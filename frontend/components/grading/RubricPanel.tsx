import React from 'react';
import { Save, RefreshCw, MessageSquare } from 'lucide-react';
import { clsx } from 'clsx';

interface RubricItem {
  id: string;
  criteria: string;
  maxScore: number;
  description: string;
}

interface RubricPanelProps {
  rubrics: RubricItem[];
  scores: Record<string, number>;
  onScoreChange: (id: string, score: number) => void;
  note: string;
  onNoteChange: (val: string) => void;
  totalScore?: number;
}

export default function RubricPanel({ rubrics, scores, onScoreChange, note, onNoteChange }: RubricPanelProps) {
  return (
    <div className="flex flex-col h-full bg-white relative">
      {/* Header */}
      <div className="h-14 bg-white border-b border-slate-200 flex items-center px-6 shrink-0 shadow-sm z-10">
        <h2 className="text-sm font-bold text-slate-800 uppercase tracking-wider">Tiêu chí chấm điểm (Rubric)</h2>
      </div>

      {/* Scrollable Content */}
      <div className="flex-1 overflow-y-auto p-6 space-y-6 bg-slate-50/50 custom-scrollbar pb-28">
        
        {/* Rubrics List */}
        <div className="space-y-4">
          {rubrics.map((r) => {
            const currentScore = scores[r.id] ?? 0;
            const isFull = currentScore === r.maxScore;
            
            return (
              <div key={r.id} className="bg-white border border-slate-200 rounded-xl p-4 shadow-sm transition-all hover:shadow-md hover:border-indigo-200">
                <div className="flex justify-between items-start gap-4 mb-3">
                  <div>
                    <h3 className="font-semibold text-slate-800 text-sm leading-tight">{r.criteria}</h3>
                    <p className="text-xs text-slate-500 mt-1 leading-relaxed">{r.description}</p>
                  </div>
                  <div className="bg-slate-100 px-2 py-1 rounded text-xs font-semibold text-slate-600 shrink-0">
                    Max: {r.maxScore}
                  </div>
                </div>

                {/* Score Input / Buttons */}
                <div className="flex items-center gap-2 mt-4">
                  {[0, r.maxScore * 0.25, r.maxScore * 0.5, r.maxScore * 0.75, r.maxScore].map((val) => (
                    <button
                      key={val}
                      onClick={() => onScoreChange(r.id, val)}
                      className={clsx(
                        "flex-1 py-1.5 rounded-md text-xs font-medium border transition-all",
                        currentScore === val 
                          ? isFull
                            ? "bg-emerald-50 text-emerald-700 border-emerald-200"
                            : "bg-indigo-50 text-indigo-700 border-indigo-200"
                          : "bg-white text-slate-500 border-slate-200 hover:bg-slate-50 hover:border-slate-300"
                      )}
                    >
                      {val}
                    </button>
                  ))}
                  
                  <div className="w-px h-6 bg-slate-200 mx-1"></div>
                  
                  <input
                    type="number"
                    min="0"
                    max={r.maxScore}
                    step="0.5"
                    value={scores[r.id] ?? ''}
                    onChange={(e) => onScoreChange(r.id, Math.min(r.maxScore, Math.max(0, Number(e.target.value))))}
                    className="w-16 text-sm py-1.5 px-2 border border-slate-200 rounded-md focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent text-center font-medium"
                    placeholder="Điểm"
                  />
                </div>
              </div>
            );
          })}
        </div>

        {/* Note Section */}
        <div className="bg-white border border-slate-200 rounded-xl p-4 shadow-sm mt-8">
          <div className="flex items-center gap-2 mb-3">
            <MessageSquare size={16} className="text-slate-400" />
            <h3 className="font-semibold text-slate-800 text-sm">Nhận xét chung</h3>
          </div>
          <textarea
            value={note}
            onChange={(e) => onNoteChange(e.target.value)}
            placeholder="Nhập nhận xét cho sinh viên..."
            className="w-full min-h-[120px] p-3 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent resize-y text-slate-700 bg-slate-50"
          />
        </div>
      </div>

      {/* Floating Action Bar */}
      <div className="absolute bottom-0 left-0 right-0 p-4 bg-white border-t border-slate-200 shadow-[0_-4px_12px_rgba(0,0,0,0.05)]">
        <div className="flex items-center justify-between gap-4 max-w-full">
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-800 rounded-lg transition-colors">
            <RefreshCw size={16} />
            Auto-grade
          </button>
          
          <button className="flex-1 flex items-center justify-center gap-2 px-6 py-2.5 bg-gradient-to-r from-indigo-600 to-blue-600 hover:from-indigo-700 hover:to-blue-700 text-white text-sm font-semibold rounded-lg shadow-sm shadow-indigo-600/25 transition-all active:scale-[0.98]">
            <Save size={18} />
            Lưu & Chuyển bài tiếp theo
          </button>
        </div>
      </div>
    </div>
  );
}
