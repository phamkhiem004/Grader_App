import React, { useState } from 'react';
import { FileCode2, FileText, Download, CodeXml } from 'lucide-react';
import { clsx } from 'clsx';

interface FileData {
  name: string;
  content: string;
}

interface SubmissionViewerProps {
  files: FileData[];
}

export default function SubmissionViewer({ files }: SubmissionViewerProps) {
  const [activeTab, setActiveTab] = useState(0);

  return (
    <div className="flex flex-col h-full bg-slate-50">
      {/* File Tabs */}
      <div className="flex bg-slate-100 border-b border-slate-200 px-2 pt-2 gap-1 overflow-x-auto shrink-0 custom-scrollbar">
        {files.map((file, idx) => (
          <button
            key={idx}
            onClick={() => setActiveTab(idx)}
            className={clsx(
              "flex items-center gap-2 px-4 py-2 rounded-t-lg text-sm font-medium transition-all",
              activeTab === idx
                ? "bg-white text-indigo-700 border-t border-x border-slate-200 shadow-[0_-2px_4px_rgba(0,0,0,0.02)]"
                : "text-slate-500 hover:bg-slate-200 hover:text-slate-700"
            )}
          >
            <FileCode2 size={16} className={activeTab === idx ? "text-indigo-500" : "text-slate-400"} />
            {file.name}
          </button>
        ))}
      </div>

      {/* Toolbar */}
      <div className="h-10 bg-white border-b border-slate-100 flex items-center justify-between px-4 shrink-0">
        <div className="text-xs text-slate-500 flex items-center gap-1.5">
          <CodeXml size={14} />
          <span>Read-only view</span>
        </div>
        <button className="text-xs font-medium text-slate-500 hover:text-indigo-600 flex items-center gap-1.5 transition-colors">
          <Download size={14} />
          Tải file
        </button>
      </div>

      {/* Code Editor Mock */}
      <div className="flex-1 overflow-auto bg-[#1e1e1e] p-4 font-mono text-[13px] leading-relaxed text-slate-300">
        <pre>
          <code>
            {files[activeTab]?.content || '// No content available'}
          </code>
        </pre>
      </div>
    </div>
  );
}
