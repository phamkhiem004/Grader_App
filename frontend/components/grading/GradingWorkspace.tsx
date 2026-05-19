"use client";

import React, { useState } from 'react';
import WorkspaceHeader from './WorkspaceHeader';
import SubmissionViewer from './SubmissionViewer';
import RubricPanel from './RubricPanel';

// Mock Data
const MOCK_STUDENT = {
  id: 'HE123456',
  name: 'Nguyễn Văn A',
  status: 'PENDING', // PENDING, GRADED, ERROR
  submittedAt: '2026-05-19 14:30',
  files: [
    { name: 'main.dart', content: 'void main() {\n  runApp(MyApp());\n}' },
    { name: 'login_screen.dart', content: 'class LoginScreen extends StatelessWidget {\n  ...\n}' },
    { name: 'calculator.dart', content: 'class Calculator {\n  int add(int a, int b) => a + b;\n}' },
  ]
};

const MOCK_RUBRIC = [
  { id: 'R1', criteria: 'Giao diện Đăng nhập (UI)', maxScore: 2.0, description: 'Thiết kế đúng form, có validate email/password cơ bản.' },
  { id: 'R2', criteria: 'Chức năng tính toán', maxScore: 4.0, description: 'Logic phép cộng trừ nhân chia chính xác, xử lý chia 0.' },
  { id: 'R3', criteria: 'Chuyển trang (Navigation)', maxScore: 2.0, description: 'Chuyển từ Login sang Home mượt mà, truyền đúng tham số.' },
  { id: 'R4', criteria: 'Code clean & Pattern', maxScore: 2.0, description: 'Tách component hợp lý, không hardcode string nhiều.' },
];

export default function GradingWorkspace() {
  const [scores, setScores] = useState<Record<string, number>>({});
  const [feedback, setFeedback] = useState('');

  const handleScoreChange = (rubricId: string, score: number) => {
    setScores(prev => ({ ...prev, [rubricId]: score }));
  };

  const totalScore = Object.values(scores).reduce((sum, s) => sum + s, 0);

  return (
    <div className="flex flex-col h-screen w-full bg-slate-50 text-slate-800 font-sans">
      <WorkspaceHeader student={MOCK_STUDENT} totalScore={totalScore} maxTotal={10} />
      
      <div className="flex flex-1 overflow-hidden">
        {/* Left Pane: Submission Viewer */}
        <div className="w-7/12 border-r border-slate-200 bg-white flex flex-col shadow-[2px_0_8px_-4px_rgba(0,0,0,0.1)] z-10 relative">
          <SubmissionViewer files={MOCK_STUDENT.files} />
        </div>

        {/* Right Pane: Rubric & Feedback */}
        <div className="w-5/12 bg-slate-50 flex flex-col">
          <RubricPanel 
            rubrics={MOCK_RUBRIC} 
            scores={scores} 
            onScoreChange={handleScoreChange}
            feedback={feedback}
            onFeedbackChange={setFeedback}
            totalScore={totalScore}
          />
        </div>
      </div>
    </div>
  );
}
